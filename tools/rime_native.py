"""Small ctypes adapter used only to compile/test official Windows librime data."""
import ctypes as C
import os
import re
from pathlib import Path


class Traits(C.Structure):
    _fields_ = [('data_size', C.c_int)] + [(n, C.c_char_p) for n in [
        'shared_data_dir', 'user_data_dir', 'distribution_name', 'distribution_code_name',
        'distribution_version', 'app_name']] + [('modules', C.c_void_p), ('min_log_level', C.c_int)] + [
        (n, C.c_char_p) for n in ['log_dir', 'prebuilt_data_dir', 'staging_dir']]


class Candidate(C.Structure):
    _fields_ = [('text', C.c_char_p), ('comment', C.c_char_p), ('reserved', C.c_void_p)]


class Composition(C.Structure):
    _fields_ = [(n, C.c_int) for n in ['length', 'cursor_pos', 'sel_start', 'sel_end']] + [('preedit', C.c_char_p)]


class Menu(C.Structure):
    _fields_ = [(n, C.c_int) for n in ['page_size', 'page_no', 'is_last_page',
                 'highlighted_candidate_index', 'num_candidates']] + [
                 ('candidates', C.POINTER(Candidate)), ('select_keys', C.c_char_p)]


class Context(C.Structure):
    _fields_ = [('data_size', C.c_int), ('composition', Composition), ('menu', Menu),
               ('commit_text_preview', C.c_char_p), ('select_labels', C.c_void_p)]


def text(value):
    return value.decode('utf8') if value else ''


class Rime:
    def __init__(self, library, header, shared, user):
        self.dll_dir = os.add_dll_directory(str(Path(library).parent.resolve()))
        self.lib = C.CDLL(str(Path(library).resolve()))
        self.lib.rime_get_api.restype = C.c_void_p
        self.api = self.lib.rime_get_api()
        h = Path(header).read_text(encoding='utf8')
        h = h[h.index('typedef struct RIME_FLAVORED(rime_api_t)'):]
        h = h[:h.index('} RIME_FLAVORED(RimeApi);')]
        self.names = re.findall(r'\(\*(\w+)\)', h)
        self.traits = Traits()
        t = self.traits
        t.data_size = C.sizeof(t) - 4
        t.shared_data_dir = str(Path(shared).resolve()).encode()
        t.user_data_dir = str(Path(user).resolve()).encode()
        t.app_name = b'rime.kongye'; t.min_log_level = 2; t.log_dir = b''
        # A compiled plugin is not necessarily in librime's default module group.
        self.modules = (C.c_char_p * 5)(b'default', b'deployer', b'octagram', b'lua', None)
        t.modules = C.cast(self.modules, C.c_void_p)
        self.call('setup', None, (C.c_void_p, C.addressof(t)))
        self.call('initialize', None, (C.c_void_p, C.addressof(t)))
        self.sessions = {}

    def call(self, name, result, *args):
        address = C.c_void_p.from_address(self.api + 8 + self.names.index(name) * 8).value
        return C.CFUNCTYPE(result, *[t for t, _ in args])(address)(*[v for _, v in args])

    def deploy(self, path):
        self.call('deployer_initialize', None, (C.c_void_p, C.addressof(self.traits)))
        if not self.call('deploy_schema', C.c_int, (C.c_char_p, str(Path(path).resolve()).encode())):
            raise RuntimeError('Rime deployment failed: ' + str(path))

    def query(self, schema, raw):
        if schema not in self.sessions:
            s = self.call('create_session', C.c_size_t)
            if not self.call('select_schema', C.c_int, (C.c_size_t, s), (C.c_char_p, schema.encode())):
                raise RuntimeError('Cannot select ' + schema)
            self.call('set_option', None, (C.c_size_t, s), (C.c_char_p, b'_no_learning'), (C.c_int, 1))
            self.sessions[schema] = s
        s = self.sessions[schema]
        self.call('clear_composition', None, (C.c_size_t, s))
        self.call('simulate_key_sequence', C.c_int, (C.c_size_t, s), (C.c_char_p, raw.encode()))
        c = Context(); c.data_size = C.sizeof(c) - 4
        try:
            self.call('get_context', C.c_int, (C.c_size_t, s), (C.c_void_p, C.addressof(c)))
            out = []
            for i in range(c.menu.num_candidates):
                cand = c.menu.candidates[i]
                fields = text(cand.comment).split('\x1f')
                if len(fields) < 4: raise RuntimeError('Missing Lua candidate metadata')
                out.append(dict(text=text(cand.text), pinyin=fields[0], end=int(fields[1]),
                                quality=float(fields[2]), preedit=fields[3], type=fields[4] if len(fields)>4 else 'unknown'))
            return text(c.composition.preedit), out
        finally:
            self.call('free_context', C.c_int, (C.c_void_p, C.addressof(c)))

    def close(self):
        self.call('finalize', None)
        self.sessions.clear()
