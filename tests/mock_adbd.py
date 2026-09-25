#!/usr/bin/env python3
"""模拟 adbd：按 AOSP packages/modules/adb 的语义实现 CNXN/AUTH/OPEN/WRTE + sync(SEND_V1) + shell。
用于验证 carhelper 手机端自研 ADB 客户端（AdbClient.java）实现是否正确。

复刻要点：
  1. 传输层：24 字节包头（cmd/arg0/arg1/len/crc/magic），OPEN 成功必须回 OKAY(本端 id, 对端 id)。
  2. 服务流：真 adbd 的 file_sync_service / shell 读的是「已被 adb 拆包后的 fd」，
     所以本 mock 用 Stream 类负责 WRTE 拆包、逐包回 OKAY（流控）。
  3. sync SEND_V1：handle_sync_command 读 SyncRequest{id, path_length} + name，
     do_send_v1 要求 name 形如 "<path>,<mode>"（find_last_of(',')，mode 用 strtoul(base 0)），
     否则回 SendSyncFail("missing , in ID_SEND_V1")。
"""
import base64
import hashlib
import os
import random
import socket
import struct
import sys
import threading
import zlib

CNXN = int.from_bytes(b'CNXN', 'little')
AUTH = int.from_bytes(b'AUTH', 'little')
OPEN = int.from_bytes(b'OPEN', 'little')
OKAY = int.from_bytes(b'OKAY', 'little')
CLSE = int.from_bytes(b'CLSE', 'little')
WRTE = int.from_bytes(b'WRTE', 'little')

SEND = int.from_bytes(b'SEND', 'little')
DATA = int.from_bytes(b'DATA', 'little')
DONE = int.from_bytes(b'DONE', 'little')
QUIT = int.from_bytes(b'QUIT', 'little')

def c_strtoul(s):
    """复刻 C 的 strtoul(s, NULL, 0)：以 0 开头按八进制解析（Python 的 int(s,0) 不接受前导 0）。"""
    s = s.strip()
    if s.lower().startswith('0x'):
        return int(s, 16)
    if s.startswith('0') and len(s) > 1:
        return int(s, 8)
    return int(s, 10)


MAX_PAYLOAD = 1024 * 1024
SYNC_DATA_MAX = 64 * 1024

log_lock = threading.Lock()


def log(*a):
    with log_lock:
        print('  [adbd]', *a, flush=True)


def recvn(c, n):
    buf = b''
    while len(buf) < n:
        d = c.recv(n - len(buf))
        if not d:
            raise EOFError('eof')
        buf += d
    return buf


def send_msg(c, cmd, a0, a1, data=b''):
    if len(data) > MAX_PAYLOAD:
        raise ValueError('oversized packet %d' % len(data))
    crc = zlib.crc32(data) & 0xffffffff
    c.sendall(struct.pack('<6I', cmd, a0, a1, len(data), crc, cmd ^ 0xffffffff) + data)


def read_msg(c):
    hdr = recvn(c, 24)
    cmd, a0, a1, ln, crc, magic = struct.unpack('<6I', hdr)
    if magic != (cmd ^ 0xffffffff):
        raise ValueError('bad magic')
    if ln > MAX_PAYLOAD:
        raise ValueError('oversized payload %d' % ln)
    data = recvn(c, ln) if ln else b''
    if ln and (zlib.crc32(data) & 0xffffffff) != crc:
        raise ValueError('bad crc')
    return cmd, a0, a1, data


# ---------------------------------------------------------------- 密钥校验

def rsa_verify_sha1(pub, sig, data):
    n, e = pub
    k = (n.bit_length() + 7) // 8
    if len(sig) != k:
        return False
    try:
        m = pow(int.from_bytes(sig, 'big'), e, n)
    except Exception:
        return False
    em = m.to_bytes(k, 'big')
    di = bytes.fromhex('3021300906052b0e03021a05000414') + hashlib.sha1(data).digest()
    if len(di) + 3 > k:
        return False
    return em == b'\x00\x01' + b'\xff' * (k - 3 - len(di)) + b'\x00' + di


def parse_adb_pubkey(blob):
    """严格校验 android_pubkey 524 字节小端格式（AOSP crypto_utils/android_pubkey.c）。"""
    txt = blob.split(b'\0')[0].decode()
    b64 = txt.split(' ')[0]
    raw = base64.b64decode(b64)
    assert len(raw) == 524, '公钥 blob 长度必须是 524，实际 %d' % len(raw)
    words, n0inv = struct.unpack('<2I', raw[:8])
    assert words == 64, 'modulus_size_words 必须是 64，实际 %d' % words
    n = int.from_bytes(raw[8:264], 'little')
    rr = int.from_bytes(raw[264:520], 'little')
    (e,) = struct.unpack('<I', raw[520:524])
    assert n.bit_length() > 2000, '模数不是小端 2048 位（bit_length=%d）' % n.bit_length()
    assert (-pow(n, -1, 1 << 32)) % (1 << 32) == n0inv, 'n0inv 错误'
    assert pow(2, 4096, n) == rr, 'rr 必须是完整的 2^4096 mod n（旧版只截断了低 32 位 → 车机永远验签失败）'
    log('公钥格式校验通过：524 字节小端 / words=64 / n0inv+rr 正确 / e=%d / banner=%s'
        % (e, txt.split(' ')[1] if ' ' in txt else '?'))
    return (n, e), b64


# ---------------------------------------------------------------- 服务流

class Stream(object):
    """一条 adb 服务流：负责把 WRTE 拆成字节流 + 逐包回 OKAY。"""

    def __init__(self, c, local):
        self.c = c
        self.local = local
        self.buf = bytearray()
        self.eof = False
        self.closed = False

    def _pump(self):
        """读一个 adb 报文并处理（一次一个，勿写成 while not buf 的空转）。"""
        cmd, a0, a1, data = read_msg(self.c)
        if cmd == WRTE and a1 == self.local:
            send_msg(self.c, OKAY, self.local, self.local)
            if len(data) == 0:
                self.eof = True          # 客户端关闭 stdin
            else:
                self.buf += data
        elif cmd == CLSE:
            self.closed = True
        else:
            pass  # OKAY（对我方写入的确认）等 → 忽略

    def read_exact(self, n):
        while len(self.buf) < n:
            self._pump()
            if len(self.buf) < n and (self.eof or self.closed):
                raise EOFError('stream closed early')
        out = bytes(self.buf[:n])
        del self.buf[:n]
        return out

    def read_all(self):
        while not self.eof and not self.closed:
            self._pump()
        out = bytes(self.buf)
        del self.buf[:]
        return out

    def write(self, data):
        send_msg(self.c, WRTE, self.local, self.local, data)


class Adbd(object):
    def __init__(self, keys_file, expect_data=None):
        self.keys_file = keys_file
        self.authorized = set()
        self.pubs = []
        if os.path.exists(keys_file):
            for line in open(keys_file).read().split():
                if line.strip():
                    self.authorized.add(line.strip())
                    try:
                        self.pubs.append(parse_adb_pubkey((line.strip() + ' mock\0').encode())[0])
                    except Exception as e:
                        log('已知公钥解析失败: %r' % (e,))
        self.expect = expect_data or {}
        self.received = {}

    def serve(self, port):
        srv = socket.socket()
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(('127.0.0.1', port))
        srv.listen(4)
        print('mock adbd listening on %d ; known keys=%d' % (port, len(self.authorized)), flush=True)
        while True:
            c, _ = srv.accept()
            threading.Thread(target=self.session, args=(c,), daemon=True).start()

    def session(self, c):
        try:
            self.handshake(c)
            while True:
                cmd, a0, a1, data = read_msg(c)
                if cmd == OPEN:
                    name = data.split(b'\0')[0].decode()
                    log('OPEN %r (client local=%d)' % (name, a0))
                    if name.startswith('sync:'):
                        send_msg(c, OKAY, a0, a0)
                        self.sync_service(c, a0)
                    elif name.startswith('shell:'):
                        self.shell_service(c, a0, name[6:])
                    elif name.startswith('exec:'):
                        self.shell_service(c, a0, name[5:])
                    else:
                        log('unknown service → CLSE')
                        send_msg(c, CLSE, a0, a0)
                elif cmd == CLSE:
                    log('CLSE from client（忽略，连接继续存活）')
                elif cmd in (OKAY, WRTE):
                    log('stray %s（忽略）' % ('OKAY' if cmd == OKAY else 'WRTE'))
                else:
                    log('unexpected %s → 结束会话' % hex(cmd))
                    return
        except EOFError:
            log('client closed')
        except Exception as e:
            log('session error: %r' % (e,))
        finally:
            try:
                c.close()
            except Exception:
                pass

    def handshake(self, c):
        cmd, a0, a1, data = read_msg(c)
        assert cmd == CNXN, 'expected CNXN'
        log('CNXN version=%#x maxdata=%d banner=%r' % (a0, a1, data.split(b'\0')[0].decode()))
        while True:
            token = bytes(random.getrandbits(8) for _ in range(20))
            send_msg(c, AUTH, 1, 0, token)
            cmd, a0, a1, data = read_msg(c)
            if cmd != AUTH:
                raise ValueError('expected AUTH, got %s' % hex(cmd))
            if a0 == 3:
                pub, b64 = parse_adb_pubkey(data)
                log('公钥结构校验通过：e=%d n0inv/rr 正确，banner=%s'
                    % (pub[1], data.split(b'\0')[0].decode().split(' ')[1]))
                log('⚠ 收到公钥 → 车机端会弹授权框（本 mock 视为车主点了“允许”）')
                if b64 not in self.authorized:
                    self.authorized.add(b64)
                    self.pubs.append(pub)
                    open(self.keys_file, 'w').write('\n'.join(sorted(self.authorized)))
                continue
            if a0 == 2:
                for pub in self.pubs:
                    if rsa_verify_sha1(pub, data, token):
                        log('✅ 签名校验通过（命中已授权密钥）→ 设备就绪')
                        send_msg(c, CNXN, 0x01000000, MAX_PAYLOAD, b'device::mock\0')
                        return
                log('签名与已授权密钥不匹配 → 再发 AUTH(TOKEN)')
                continue
            raise ValueError('unexpected AUTH arg0=%d' % a0)

    # ---- sync 服务
    def sync_service(self, c, local):
        self.silent = False   # 每个 sync 服务重置（SILENT 只对含 SILENT 的路径生效）
        st = Stream(c, local)
        while True:
            sid, plen = struct.unpack('<II', st.read_exact(8))
            if plen > 1024:
                self.sync_fail(st, 'path too long')
                return
            name = st.read_exact(plen).decode('utf-8', 'replace') if plen else ''
            if sid == SEND:
                comma = name.rfind(',')
                if comma < 0:
                    self.sync_fail(st, 'missing , in ID_SEND_V1')
                    return
                path = name[:comma]
                if 'FAILTEST' in path:
                    self.sync_fail(st, 'missing , in ID_SEND_V1')
                    return
                mode = c_strtoul(name[comma + 1:])
                self.silent = 'SILENT' in path
                log('SEND path=%s mode=0%o%s' % (path, mode, '  [静默模式：不发任何 sync 应答]' if self.silent else ''))
                if mode != 0o644:
                    log('!! 权限不是 0644 (%o)' % mode)
                # 现代 adbd（daemon/file_sync_service.cpp）对 SEND 请求本身**不回任何东西**
                buf = bytearray()
                while True:
                    did, size = struct.unpack('<II', st.read_exact(8))
                    if did == DATA:
                        if size > SYNC_DATA_MAX:
                            log('!! DATA 块超限 %d' % size)
                            self.sync_fail(st, 'data too large')
                            return
                        buf += st.read_exact(size)
                        self.sync_okay(st)
                    elif did == DONE:
                        log('DONE（mtime=%d）累计 %d 字节' % (size, len(buf)))
                        self.sync_okay(st)
                        self.received[path] = bytes(buf)
                        exp = self.expect.get(path)
                        if exp is not None and bytes(buf) != exp:
                            log('❌ 内容不一致：%d != %d' % (len(buf), len(exp)))
                        break
                    else:
                        self.sync_fail(st, 'bad id %s' % hex(did))
                        return
            elif sid == QUIT:
                log('QUIT → 结束 sync 会话')
                send_msg(c, CLSE, local, local)
                return
            else:
                self.sync_fail(st, 'unknown command %s' % hex(sid))
                return

    silent = False

    def sync_okay(self, st):
        if self.silent:
            return
        st.write(b'OKAY' + struct.pack('<I', 0))

    def sync_fail(self, st, msg):
        log('sync FAIL: %s' % msg)
        if self.silent:
            return
        st.write(b'FAIL' + struct.pack('<I', len(msg)) + msg.encode())

    # ---- shell 服务
    def shell_service(self, c, local, command):
        log('shell: %r' % command)
        send_msg(c, OKAY, local, local)   # OPEN 成功必须先回 OKAY
        st = Stream(c, local)
        stdin = st.read_all()
        cmdline = command.strip()
        out = b''
        if cmdline.startswith('cat > '):
            path = cmdline[len('cat > '):].strip()
            self.received[path] = stdin
            exp = self.expect.get(path)
            if exp is not None and stdin != exp:
                log('❌ shell 通道内容不一致：%d != %d' % (len(stdin), len(exp)))
            log('shell cat 收到 %d 字节' % len(stdin))
        elif cmdline.startswith('mockinfo '):
            path = cmdline.split(' ', 1)[1].strip()
            b = self.received.get(path, b'')
            out = ('size=%d sha256=%s\n' % (len(b), hashlib.sha256(b).hexdigest())).encode()
        elif cmdline.startswith('echo '):
            out = (cmdline[5:] + '\n').encode()
        elif 'install' in cmdline and '-S' in cmdline:
            # 复刻 `cmd package install -S <size>`：从 stdin 读满 size 字节后输出结果
            toks = cmdline.split()
            size = int(toks[toks.index('-S') + 1])
            got = len(stdin)
            if got == size:
                out = b'Success\n'
            else:
                out = ('Failure [INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES: read %d of %d]\n'
                       % (got, size)).encode()
        else:
            out = b'mock-ok\n'
        log('shell 输出 %d 字节: %r' % (len(out), out[:60]))
        if out:
            st.write(out)
        st.write(b'')  # 关闭 stdout
        if 'NOCLSE' in command and cmdline.startswith('cat > '):
            log('shell 服务结束不发 CLSE（模拟本车机行为）')
            return
        send_msg(c, CLSE, local, local)


if __name__ == '__main__':
    Adbd(sys.argv[2]).serve(int(sys.argv[1]))
