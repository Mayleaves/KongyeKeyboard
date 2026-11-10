package com.kongye.keyboard;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** librime 1.17's versioned C ABI. arm64 only, as declared by the app's ABI filter.
 * Layouts are verified against upstream rime_api.h (int Bool, uintptr_t session).
 * No pointers returned by Rime escape the lifetime of their owning context.
 */
public final class RimeBridge {
    private static final String[] API = ("setup,set_notification_handler,initialize,finalize,start_maintenance,is_maintenance_mode,join_maintenance_thread,deployer_initialize,prebuild,deploy,deploy_schema,deploy_config_file,sync_user_data,create_session,find_session,destroy_session,cleanup_stale_sessions,cleanup_all_sessions,process_key,commit_composition,clear_composition,get_commit,free_commit,get_context,free_context,get_status,free_status,set_option,get_option,set_property,get_property,get_schema_list,free_schema_list,get_current_schema,select_schema,schema_open,config_open,config_close,config_get_bool,config_get_int,config_get_double,config_get_string,config_get_cstring,config_update_signature,config_begin_map,config_next,config_end,simulate_key_sequence,register_module,find_module,run_task,get_shared_data_dir,get_user_data_dir,get_sync_dir,get_user_id,get_user_data_sync_dir,config_init,config_load_string,config_set_bool,config_set_int,config_set_double,config_set_string,config_get_item,config_set_item,config_clear,config_create_list,config_create_map,config_list_size,config_begin_list,get_input,get_caret_pos,select_candidate,get_version,set_caret_pos,select_candidate_on_current_page,candidate_list_begin,candidate_list_next,candidate_list_end,user_config_open,candidate_list_from_index,get_prebuilt_data_dir,get_staging_dir,commit_proto,context_proto,status_proto,get_state_label,delete_candidate,delete_candidate_on_current_page,get_state_label_abbreviated,set_input,get_shared_data_dir_s,get_user_data_dir_s,get_prebuilt_data_dir_s,get_staging_dir_s,get_sync_dir_s,highlight_candidate,highlight_candidate_on_current_page,change_page").split(",");
    private final NativeLibrary library;
    private final Map<String, Function> functions = new HashMap<>();
    private final Map<String, Long> sessions = new HashMap<>();
    private final Map<String, String> sessionInputs = new HashMap<>();
    private final Memory traits = structure(96);
    // Rime stores these trait pointers until finalize, so keep their owners alive.
    private final List<Memory> traitStrings = new ArrayList<>();
    public final String version;

    public RimeBridge(String shared, String user) {
        if (Native.POINTER_SIZE != 8) throw new IllegalStateException("Rime requires arm64");
        library = NativeLibrary.getInstance("rime");
        Pointer api = library.getFunction("rime_get_api").invokePointer(new Object[0]);
        int available = api.getInt(0) + 4;
        for (int i = 0; i < API.length && 8L + i * 8L < available; i++) {
            Pointer address = api.getPointer(8L + i * 8L);
            if (address != null) functions.put(API[i], Function.getFunction(address));
        }
        trait(8, shared); trait(16, user); trait(24, "Kongye Keyboard");
        trait(32, "kongye"); trait(40, "2.1.0"); trait(48, "rime.kongye");
        traits.setInt(64, 2); trait(72, ""); trait(80, shared + "/build");
        trait(88, shared + "/build");
        function("setup").invokeVoid(new Object[]{traits});
        function("initialize").invokeVoid(new Object[]{traits});
        version = string(function("get_version").invokePointer(new Object[0]));
    }
    private static Memory structure(int size) {
        Memory m = new Memory(size); m.clear(); m.setInt(0, size - 4); return m;
    }
    private void trait(long at, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Memory m = new Memory(bytes.length + 1); m.write(0, bytes, 0, bytes.length);
        m.setByte(bytes.length, (byte) 0); traitStrings.add(m); traits.setPointer(at, m);
    }
    private Function function(String name) {
        Function f = functions.get(name);
        if (f == null) throw new IllegalStateException("Missing Rime API: " + name);
        return f;
    }
    private static String string(Pointer p) { return p == null ? "" : p.getString(0, "UTF-8"); }
    private long session(String schema) {
        Long saved = sessions.get(schema); if (saved != null) return saved;
        long id = function("create_session").invokeLong(new Object[0]);
        if (id == 0 || function("select_schema").invokeInt(new Object[]{id, schema}) == 0)
            throw new IllegalStateException("Cannot select Rime schema " + schema);
        // Candidate lookup must never train the user dictionary. Learning is a
        // separate explicit action, gated by the IME's privacy/learning setting.
        function("set_option").invokeVoid(new Object[]{id, "_no_learning", 1});
        sessions.put(schema, id); return id;
    }
    public static final class Candidate {
        public final String text, pinyin, preedit;
        public final int end, index;
        public final double quality;
        Candidate(String text, String comment, int index) {
            String[] parts = comment.split("\u001f", -1);
            if (parts.length != 4) throw new IllegalStateException("Rime metadata unavailable");
            this.text = text; this.pinyin = parts[0]; this.end = Integer.parseInt(parts[1]);
            this.quality = Double.parseDouble(parts[2]); this.preedit = parts[3]; this.index = index;
        }
    }
    public static final class Result {
        public final String preedit;
        public final List<Candidate> candidates;
        Result(String preedit, List<Candidate> candidates) { this.preedit = preedit; this.candidates = candidates; }
    }
    public synchronized Result query(String schema, String raw) {
        long id = session(schema);
        String previous = sessionInputs.get(schema);
        // set_input rebuilds once. Replaying every skipped keystroke re-decodes
        // long sentences repeatedly when fast typing overtakes the worker.
        if (!raw.equals(previous)) function("set_input").invokeInt(new Object[]{id, raw});
        sessionInputs.put(schema, raw);
        Memory context = structure(88);
        try {
            if (function("get_context").invokeInt(new Object[]{id, context}) == 0)
                return new Result("", new ArrayList<>());
            String preedit = string(context.getPointer(24));
            int count = context.getInt(48);
            if (count < 0 || count > 100) throw new IllegalStateException("Invalid Rime menu size");
            Pointer menu = context.getPointer(56);
            List<Candidate> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++)
                result.add(new Candidate(string(menu.getPointer(i * 24L)), string(menu.getPointer(i * 24L + 8)), i));
            return new Result(preedit, result);
        } finally {
            function("free_context").invokeInt(new Object[]{context}); context.close();
        }
    }
    public synchronized void learn(String schema, String raw, String text) {
        long id = session(schema);
        Result result = query(schema, raw);
        for (Candidate c : result.candidates) {
            if (c.end == raw.length() && c.text.equals(text)) {
                function("set_option").invokeVoid(new Object[]{id, "_no_learning", 0});
                try {
                    function("select_candidate").invokeInt(new Object[]{id, (long)c.index});
                    Memory commit = structure(16);
                    try {
                        if (function("get_commit").invokeInt(new Object[]{id, commit}) != 0)
                            function("free_commit").invokeInt(new Object[]{commit});
                    } finally { commit.close(); }
                } finally {
                    function("set_option").invokeVoid(new Object[]{id, "_no_learning", 1});
                    function("clear_composition").invokeVoid(new Object[]{id});
                    sessionInputs.remove(schema);
                }
                return;
            }
        }
    }
    public synchronized void clearInput() {
        for (Long id : sessions.values()) function("clear_composition").invokeVoid(new Object[]{id});
        sessionInputs.clear();
    }
    public synchronized void close() {
        clearInput();
        function("finalize").invokeVoid(new Object[0]);
        sessions.clear();
    }
}
