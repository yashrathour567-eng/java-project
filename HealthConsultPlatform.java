import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * CareConnect - Online Health Consultation Platform
 * ---------------------------------------------------------------------------
 * A complete web application written in pure Java (JDK 17+):
 *   - Web layer      : com.sun.net.httpserver (request/response, routing, cookie sessions)
 *   - Business layer : service classes (booking rules, schedules, consultations, ...)
 *   - Data layer     : JDBC with a connection pool, CRUD and transactions (SQLite)
 *   - Concurrency    : worker thread pool, scheduled reminder + session-cleanup threads
 *   - UI             : responsive single-page front end served from this same file
 *
 * Roles: ADMIN, DOCTOR, PATIENT.   Run:  java -cp ".:lib/*" HealthConsultPlatform
 */
public class HealthConsultPlatform {

    private static final Logger LOG = Logger.getLogger("CareConnect");
    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    // =====================================================================
    // 1. DOMAIN MODEL (enums + exceptions)
    // =====================================================================

    enum Role {
        ADMIN, DOCTOR, PATIENT;

        static Role parse(String s) {
            try { return valueOf(s.trim().toUpperCase()); }
            catch (Exception e) { throw new AppException(400, "Invalid role"); }
        }
    }

    enum Status { SCHEDULED, COMPLETED, CANCELLED }

    /** Checked-style business error carrying an HTTP status code. */
    static class AppException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;
        AppException(int status, String message) { super(message); this.status = status; }
    }

    record Reply(String message, Object data) {}

    // =====================================================================
    // 2. SMALL UTILITIES (validation, hashing, JSON)
    // =====================================================================

    static final class Util {
        static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");

        static LocalDate date(String s) {
            try { return LocalDate.parse(s); }
            catch (DateTimeParseException e) { throw new AppException(400, "Invalid date, expected yyyy-MM-dd"); }
        }

        static LocalTime time(String s) {
            try { return LocalTime.parse(s, HM); }
            catch (DateTimeParseException e) { throw new AppException(400, "Invalid time, expected HH:mm"); }
        }

        static int num(Object o) { return o == null ? 0 : ((Number) o).intValue(); }
        static String s(Object o) { return o == null ? "" : o.toString(); }
    }

    static final class Security {
        private static final SecureRandom RNG = new SecureRandom();

        static String randomToken(int bytes) {
            byte[] b = new byte[bytes];
            RNG.nextBytes(b);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        }

        static String hash(String password, String salt) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] d = (salt + password).getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < 20_000; i++) d = md.digest(d);   // simple key stretching
                return Base64.getEncoder().encodeToString(d);
            } catch (Exception e) { throw new IllegalStateException(e); }
        }

        static boolean verify(String password, String salt, String expected) {
            return MessageDigest.isEqual(hash(password, salt).getBytes(StandardCharsets.UTF_8),
                    expected.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Minimal JSON writer (no external libraries). */
    static final class Json {
        static String write(Object o) { StringBuilder sb = new StringBuilder(); w(sb, o); return sb.toString(); }

        private static void w(StringBuilder sb, Object o) {
            if (o == null) sb.append("null");
            else if (o instanceof Number || o instanceof Boolean) sb.append(o);
            else if (o instanceof Map<?, ?> m) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    str(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    w(sb, e.getValue());
                }
                sb.append('}');
            } else if (o instanceof Collection<?> c) {
                sb.append('[');
                boolean first = true;
                for (Object x : c) { if (!first) sb.append(','); first = false; w(sb, x); }
                sb.append(']');
            } else str(sb, o.toString());
        }

        private static void str(StringBuilder sb, String s) {
            sb.append('"');
            for (char ch : s.toCharArray()) {
                switch (ch) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> { if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch)); else sb.append(ch); }
                }
            }
            sb.append('"');
        }
    }

    // =====================================================================
    // 3. DATA ACCESS LAYER (JDBC, connection pool, transactions)
    // =====================================================================

    static final class Db {
        interface Work<T> { T run(Connection c) throws SQLException; }

        private static final int POOL_SIZE = 4;
        private static final BlockingQueue<Connection> POOL = new ArrayBlockingQueue<>(POOL_SIZE);
        static final String URL = System.getProperty("db.url", "jdbc:sqlite:healthconsult.db");

        static void init() throws SQLException {
            for (int i = 0; i < POOL_SIZE; i++) {
                Connection c = DriverManager.getConnection(URL);
                try (Statement st = c.createStatement()) {
                    st.execute("PRAGMA foreign_keys=ON");
                    st.execute("PRAGMA busy_timeout=5000");
                }
                POOL.add(c);
            }
            tx(c -> {
                try (Statement st = c.createStatement()) {
                    for (String ddl : Schema.DDL) st.execute(ddl);
                }
                return null;
            });
        }

        static Connection take() {
            try {
                Connection c = POOL.poll(10, TimeUnit.SECONDS);
                if (c == null) throw new AppException(503, "Server is busy, please retry");
                return c;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AppException(503, "Interrupted");
            }
        }

        static void give(Connection c) { POOL.offer(c); }

        static void closeAll() {
            for (Connection c : POOL) { try { c.close(); } catch (SQLException ignored) { } }
        }

        static RuntimeException wrap(SQLException e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("users.email")) return new AppException(409, "That email is already registered");
            if (m.contains("UNIQUE")) return new AppException(409, "That slot was just booked by someone else");
            LOG.log(Level.SEVERE, "SQL error", e);
            return new AppException(500, "Database error");
        }

        // ---- connection-bound helpers (used inside transactions) ----
        static List<Map<String, Object>> query(Connection c, String sql, Object... p) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, p);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                        rows.add(row);
                    }
                    return rows;
                }
            }
        }

        static Map<String, Object> queryOne(Connection c, String sql, Object... p) throws SQLException {
            List<Map<String, Object>> r = query(c, sql, p);
            return r.isEmpty() ? null : r.get(0);
        }

        static int run(Connection c, String sql, Object... p) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement(sql)) { bind(ps, p); return ps.executeUpdate(); }
        }

        static long insert(Connection c, String sql, Object... p) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, p);
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) { return k.next() ? k.getLong(1) : 0; }
            }
        }

        private static void bind(PreparedStatement ps, Object[] p) throws SQLException {
            for (int i = 0; i < p.length; i++) ps.setObject(i + 1, p[i]);
        }

        // ---- self-managed helpers (borrow + return a pooled connection) ----
        static <T> T read(Work<T> w) {
            Connection c = take();
            try { return w.run(c); }
            catch (SQLException e) { throw wrap(e); }
            finally { give(c); }
        }

        /** Runs the work in a transaction: commit on success, rollback on any failure. */
        static <T> T tx(Work<T> w) {
            Connection c = take();
            try {
                c.setAutoCommit(false);
                T r = w.run(c);
                c.commit();
                return r;
            } catch (SQLException e) {
                rollback(c);
                throw wrap(e);
            } catch (RuntimeException e) {
                rollback(c);
                throw e;
            } finally {
                try { c.setAutoCommit(true); } catch (SQLException ignored) { }
                give(c);
            }
        }

        private static void rollback(Connection c) { try { c.rollback(); } catch (SQLException ignored) { } }

        static List<Map<String, Object>> q(String sql, Object... p) { return read(c -> query(c, sql, p)); }
        static Map<String, Object> q1(String sql, Object... p) { return read(c -> queryOne(c, sql, p)); }
        static int run(String sql, Object... p) { return tx(c -> run(c, sql, p)); }
        static long ins(String sql, Object... p) { return tx(c -> insert(c, sql, p)); }
    }

    /** Relational schema (3NF). See README for the ER diagram. */
    static final class Schema {
        static final String[] DDL = {
            "CREATE TABLE IF NOT EXISTS users(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, email TEXT NOT NULL UNIQUE, " +
                "password_hash TEXT NOT NULL, salt TEXT NOT NULL, " +
                "role TEXT NOT NULL CHECK(role IN ('ADMIN','DOCTOR','PATIENT')), " +
                "specialization TEXT, phone TEXT, active INTEGER NOT NULL DEFAULT 1, " +
                "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')))",
            "CREATE TABLE IF NOT EXISTS schedules(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "doctor_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, " +
                "weekday INTEGER NOT NULL CHECK(weekday BETWEEN 1 AND 7), " +
                "start_time TEXT NOT NULL, end_time TEXT NOT NULL, slot_minutes INTEGER NOT NULL, " +
                "UNIQUE(doctor_id, weekday))",
            "CREATE TABLE IF NOT EXISTS appointments(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "patient_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, " +
                "doctor_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, " +
                "appt_date TEXT NOT NULL, appt_time TEXT NOT NULL, reason TEXT, " +
                "status TEXT NOT NULL DEFAULT 'SCHEDULED' CHECK(status IN ('SCHEDULED','COMPLETED','CANCELLED')), " +
                "reminded INTEGER NOT NULL DEFAULT 0, " +
                "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')))",
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_slot ON appointments(doctor_id, appt_date, appt_time) WHERE status<>'CANCELLED'",
            "CREATE TABLE IF NOT EXISTS consultations(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "appointment_id INTEGER NOT NULL UNIQUE REFERENCES appointments(id) ON DELETE CASCADE, " +
                "diagnosis TEXT, advice TEXT NOT NULL, prescription TEXT, " +
                "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')))",
            "CREATE TABLE IF NOT EXISTS health_records(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "patient_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, " +
                "record_type TEXT NOT NULL, title TEXT NOT NULL, details TEXT, record_date TEXT NOT NULL, " +
                "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')))",
            "CREATE TABLE IF NOT EXISTS messages(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "sender_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, " +
                "receiver_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, " +
                "body TEXT NOT NULL, is_read INTEGER NOT NULL DEFAULT 0, " +
                "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')))",
            "CREATE TABLE IF NOT EXISTS notifications(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, " +
                "text TEXT NOT NULL, seen INTEGER NOT NULL DEFAULT 0, " +
                "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')))",
            "CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)"
        };
    }

    // =====================================================================
    // 4. SERVICE LAYER (business rules)
    // =====================================================================

    static final class Settings {
        static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
        static {
            DEFAULTS.put("clinic_name", "CareConnect");
            DEFAULTS.put("booking_window_days", "30");
            DEFAULTS.put("max_active_per_patient", "5");
            DEFAULTS.put("default_slot_minutes", "30");
            DEFAULTS.put("allow_registration", "true");
        }

        static Map<String, Object> all() {
            Map<String, Object> out = new LinkedHashMap<>(DEFAULTS);
            for (Map<String, Object> r : Db.q("SELECT key, value FROM settings")) out.put(Util.s(r.get("key")), r.get("value"));
            return out;
        }

        static String get(String key) { return Util.s(all().get(key)); }
        static int getInt(String key) { return Integer.parseInt(get(key)); }

        static Reply update(Map<String, String> in) {
            Db.tx(c -> {
                for (String key : DEFAULTS.keySet()) {
                    if (!in.containsKey(key)) continue;
                    String v = in.get(key).trim();
                    if (v.isEmpty()) throw new AppException(400, key + " cannot be empty");
                    if (key.equals("allow_registration") && !(v.equals("true") || v.equals("false")))
                        throw new AppException(400, "allow_registration must be true or false");
                    if (key.endsWith("_days") || key.endsWith("_minutes") || key.startsWith("max_")) {
                        try { if (Integer.parseInt(v) < 1) throw new NumberFormatException(); }
                        catch (NumberFormatException e) { throw new AppException(400, key + " must be a positive number"); }
                    }
                    Db.run(c, "INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, v);
                }
                return null;
            });
            return new Reply("System settings updated successfully", all());
        }
    }

    static final class Notifications {
        static void add(Connection c, int userId, String text) throws SQLException {
            Db.insert(c, "INSERT INTO notifications(user_id,text) VALUES(?,?)", userId, text);
        }
    }

    static final class UserService {
        static final String COLS = "id,name,email,role,specialization,phone,active,created_at";

        static Map<String, Object> login(String email, String password) {
            Map<String, Object> u = Db.q1("SELECT * FROM users WHERE email=?", email.toLowerCase());
            if (u == null || !Security.verify(password, Util.s(u.get("salt")), Util.s(u.get("password_hash"))))
                throw new AppException(401, "Invalid email or password");
            if (Util.num(u.get("active")) == 0) throw new AppException(403, "This account is deactivated. Contact the administrator.");
            return Db.q1("SELECT " + COLS + " FROM users WHERE id=?", u.get("id"));
        }

        static void validate(String name, String email, String password, boolean passwordRequired) {
            if (name.length() < 2) throw new AppException(400, "Name must be at least 2 characters");
            if (!Util.EMAIL.matcher(email).matches()) throw new AppException(400, "Enter a valid email address");
            if ((passwordRequired || !password.isEmpty()) && password.length() < 6)
                throw new AppException(400, "Password must be at least 6 characters");
        }

        static long create(String name, String email, String password, Role role, String spec, String phone) {
            validate(name, email, password, true);
            String salt = Security.randomToken(16);
            return Db.ins("INSERT INTO users(name,email,password_hash,salt,role,specialization,phone) VALUES(?,?,?,?,?,?,?)",
                    name, email.toLowerCase(), Security.hash(password, salt), salt, role.name(),
                    role == Role.DOCTOR ? spec : null, phone);
        }

        static void update(int id, String name, String email, String password, Role role, String spec, String phone, boolean active) {
            validate(name, email, password, false);
            Db.tx(c -> {
                int n = Db.run(c, "UPDATE users SET name=?,email=?,role=?,specialization=?,phone=?,active=? WHERE id=?",
                        name, email.toLowerCase(), role.name(), role == Role.DOCTOR ? spec : null, phone, active ? 1 : 0, id);
                if (n == 0) throw new AppException(404, "User not found");
                if (!password.isEmpty()) setPassword(c, id, password);
                return null;
            });
        }

        static void setPassword(Connection c, int id, String password) throws SQLException {
            String salt = Security.randomToken(16);
            Db.run(c, "UPDATE users SET password_hash=?, salt=? WHERE id=?", Security.hash(password, salt), salt, id);
        }

        static void delete(int id, int actingUser) {
            if (id == actingUser) throw new AppException(409, "You cannot delete your own account");
            if (Db.run("DELETE FROM users WHERE id=?", id) == 0) throw new AppException(404, "User not found");
        }

        static void updateProfile(int id, String name, String phone, String password) {
            if (name.length() < 2) throw new AppException(400, "Name must be at least 2 characters");
            if (!password.isEmpty() && password.length() < 6) throw new AppException(400, "Password must be at least 6 characters");
            Db.tx(c -> {
                Db.run(c, "UPDATE users SET name=?, phone=? WHERE id=?", name, phone, id);
                if (!password.isEmpty()) setPassword(c, id, password);
                return null;
            });
        }
    }

    /** Computes bookable slots from a doctor's weekly schedule minus existing bookings. */
    static final class Slots {
        static List<String> free(Connection c, int doctorId, LocalDate date) throws SQLException {
            Map<String, Object> sch = Db.queryOne(c,
                    "SELECT start_time,end_time,slot_minutes FROM schedules WHERE doctor_id=? AND weekday=?",
                    doctorId, date.getDayOfWeek().getValue());
            if (sch == null) return List.of();
            Set<String> taken = new HashSet<>();
            for (Map<String, Object> r : Db.query(c,
                    "SELECT appt_time FROM appointments WHERE doctor_id=? AND appt_date=? AND status<>'CANCELLED'",
                    doctorId, date.toString())) taken.add(Util.s(r.get("appt_time")));
            LocalTime start = Util.time(Util.s(sch.get("start_time"))), end = Util.time(Util.s(sch.get("end_time")));
            int step = Util.num(sch.get("slot_minutes"));
            LocalDateTime now = LocalDateTime.now();
            List<String> out = new ArrayList<>();
            for (LocalTime t = start; !t.plusMinutes(step).isAfter(end); t = t.plusMinutes(step)) {
                String label = t.format(HM);
                if (!taken.contains(label) && LocalDateTime.of(date, t).isAfter(now)) out.add(label);
            }
            return out;
        }
    }

    static final class ScheduleService {
        static List<Map<String, Object>> forDoctor(int doctorId) {
            return Db.q("SELECT id,weekday,start_time,end_time,slot_minutes FROM schedules WHERE doctor_id=? ORDER BY weekday", doctorId);
        }

        static Reply save(int doctorId, int weekday, String start, String end, int slot) {
            if (weekday < 1 || weekday > 7) throw new AppException(400, "Invalid weekday");
            if (slot < 10 || slot > 120) throw new AppException(400, "Slot length must be between 10 and 120 minutes");
            LocalTime s = Util.time(start), e = Util.time(end);
            if (!e.isAfter(s)) throw new AppException(400, "End time must be after start time");
            Db.run("INSERT INTO schedules(doctor_id,weekday,start_time,end_time,slot_minutes) VALUES(?,?,?,?,?) " +
                    "ON CONFLICT(doctor_id,weekday) DO UPDATE SET start_time=excluded.start_time, end_time=excluded.end_time, slot_minutes=excluded.slot_minutes",
                    doctorId, weekday, s.format(HM), e.format(HM), slot);
            return new Reply("Schedule updated successfully", forDoctor(doctorId));
        }

        static Reply dayOff(int doctorId, int weekday) {
            Db.run("DELETE FROM schedules WHERE doctor_id=? AND weekday=?", doctorId, weekday);
            return new Reply("Marked as a day off", forDoctor(doctorId));
        }
    }

    static final class AppointmentService {
        static final String SELECT =
            "SELECT a.id,a.patient_id,a.doctor_id,a.appt_date,a.appt_time,a.reason,a.status," +
            "p.name AS patient_name,d.name AS doctor_name,d.specialization," +
            "c.id AS consult_id,c.diagnosis,c.advice,c.prescription,c.created_at AS advised_at " +
            "FROM appointments a JOIN users p ON p.id=a.patient_id JOIN users d ON d.id=a.doctor_id " +
            "LEFT JOIN consultations c ON c.appointment_id=a.id ";

        static List<Map<String, Object>> list(Session s) {
            return switch (s.role) {
                case ADMIN -> Db.q(SELECT + "ORDER BY a.appt_date DESC, a.appt_time DESC");
                case DOCTOR -> Db.q(SELECT + "WHERE a.doctor_id=? ORDER BY a.appt_date DESC, a.appt_time DESC", s.userId);
                case PATIENT -> Db.q(SELECT + "WHERE a.patient_id=? ORDER BY a.appt_date DESC, a.appt_time DESC", s.userId);
            };
        }

        /** Booking runs in ONE transaction: rule checks + insert + notifications commit together. */
        static Reply book(int patientId, int doctorId, String dateStr, String time, String reason) {
            LocalDate date = Util.date(dateStr);
            Util.time(time);
            if (date.isBefore(LocalDate.now())) throw new AppException(400, "Pick today or a future date");
            if (date.isAfter(LocalDate.now().plusDays(Settings.getInt("booking_window_days"))))
                throw new AppException(400, "Bookings open only " + Settings.get("booking_window_days") + " days ahead");
            int maxActive = Settings.getInt("max_active_per_patient");
            return Db.tx(c -> {
                Map<String, Object> doc = Db.queryOne(c, "SELECT id,name FROM users WHERE id=? AND role='DOCTOR' AND active=1", doctorId);
                Map<String, Object> pat = Db.queryOne(c, "SELECT id,name FROM users WHERE id=? AND role='PATIENT' AND active=1", patientId);
                if (doc == null) throw new AppException(404, "Healthcare professional not found");
                if (pat == null) throw new AppException(404, "Patient not found");
                Map<String, Object> cnt = Db.queryOne(c,
                        "SELECT COUNT(*) AS n FROM appointments WHERE patient_id=? AND status='SCHEDULED' AND appt_date>=?",
                        patientId, LocalDate.now().toString());
                if (Util.num(cnt.get("n")) >= maxActive)
                    throw new AppException(409, "Limit reached: " + maxActive + " upcoming appointments at a time");
                if (!Slots.free(c, doctorId, date).contains(time))
                    throw new AppException(409, "That time slot is not available");
                long id = Db.insert(c, "INSERT INTO appointments(patient_id,doctor_id,appt_date,appt_time,reason) VALUES(?,?,?,?,?)",
                        patientId, doctorId, date.toString(), time, reason);
                Notifications.add(c, doctorId, "New booking from " + pat.get("name") + " on " + date + " at " + time);
                Notifications.add(c, patientId, "Booked with " + doc.get("name") + " on " + date + " at " + time);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", id);
                return new Reply("Booking confirmed with " + doc.get("name") + " on " + date + " at " + time, out);
            });
        }

        static Reply cancel(Session s, int id) {
            return Db.tx(c -> {
                Map<String, Object> a = Db.queryOne(c, "SELECT * FROM appointments WHERE id=?", id);
                if (a == null) throw new AppException(404, "Appointment not found");
                boolean mine = (s.role == Role.PATIENT && Util.num(a.get("patient_id")) == s.userId)
                        || (s.role == Role.DOCTOR && Util.num(a.get("doctor_id")) == s.userId) || s.role == Role.ADMIN;
                if (!mine) throw new AppException(403, "This is not your appointment");
                if (!"SCHEDULED".equals(a.get("status"))) throw new AppException(409, "Only scheduled appointments can be cancelled");
                Db.run(c, "UPDATE appointments SET status='CANCELLED' WHERE id=?", id);
                String msg = "Appointment on " + a.get("appt_date") + " at " + a.get("appt_time") + " was cancelled";
                Notifications.add(c, Util.num(a.get("patient_id")), msg);
                Notifications.add(c, Util.num(a.get("doctor_id")), msg);
                return new Reply("Appointment cancelled", null);
            });
        }

        static Reply setStatus(int id, String status) {
            Status st;
            try { st = Status.valueOf(status.toUpperCase()); } catch (Exception e) { throw new AppException(400, "Invalid status"); }
            if (Db.run("UPDATE appointments SET status=? WHERE id=?", st.name(), id) == 0)
                throw new AppException(404, "Appointment not found");
            return new Reply("Appointment marked " + st.name().toLowerCase(), null);
        }

        static Reply delete(int id) {
            if (Db.run("DELETE FROM appointments WHERE id=?", id) == 0) throw new AppException(404, "Appointment not found");
            return new Reply("Appointment deleted", null);
        }
    }

    static final class ConsultationService {
        static Reply provide(int doctorId, int appointmentId, String diagnosis, String advice, String prescription) {
            if (advice.length() < 3) throw new AppException(400, "Please write the medical advice");
            return Db.tx(c -> {
                Map<String, Object> a = Db.queryOne(c, "SELECT * FROM appointments WHERE id=? AND doctor_id=?", appointmentId, doctorId);
                if (a == null) throw new AppException(404, "Appointment not found");
                if ("CANCELLED".equals(a.get("status"))) throw new AppException(409, "This appointment was cancelled");
                Map<String, Object> ex = Db.queryOne(c, "SELECT id FROM consultations WHERE appointment_id=?", appointmentId);
                if (ex == null)
                    Db.insert(c, "INSERT INTO consultations(appointment_id,diagnosis,advice,prescription) VALUES(?,?,?,?)",
                            appointmentId, diagnosis, advice, prescription);
                else
                    Db.run(c, "UPDATE consultations SET diagnosis=?,advice=?,prescription=? WHERE appointment_id=?",
                            diagnosis, advice, prescription, appointmentId);
                Db.run(c, "UPDATE appointments SET status='COMPLETED' WHERE id=?", appointmentId);
                Notifications.add(c, Util.num(a.get("patient_id")), "Medical advice is ready for your visit on " + a.get("appt_date"));
                return new Reply("Consultation saved and shared with the patient", null);
            });
        }

        /** Doctor can open the file of any patient they have an appointment with. */
        static Map<String, Object> patientFile(int doctorId, int patientId) {
            if (Db.q1("SELECT 1 AS x FROM appointments WHERE doctor_id=? AND patient_id=? LIMIT 1", doctorId, patientId) == null)
                throw new AppException(403, "No appointment with this patient, so their file is not available to you");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("profile", Db.q1("SELECT id,name,email,phone,created_at FROM users WHERE id=? AND role='PATIENT'", patientId));
            out.put("records", Db.q("SELECT id,record_type,title,details,record_date FROM health_records WHERE patient_id=? ORDER BY record_date DESC, id DESC", patientId));
            out.put("history", Db.q("SELECT a.appt_date,a.appt_time,d.name AS doctor_name,c.diagnosis,c.advice,c.prescription " +
                    "FROM consultations c JOIN appointments a ON a.id=c.appointment_id JOIN users d ON d.id=a.doctor_id " +
                    "WHERE a.patient_id=? ORDER BY a.appt_date DESC", patientId));
            return out;
        }

        static List<Map<String, Object>> myPatients(int doctorId) {
            return Db.q("SELECT u.id,u.name,u.email,u.phone,COUNT(a.id) AS visits,MAX(a.appt_date) AS last_visit " +
                    "FROM users u JOIN appointments a ON a.patient_id=u.id WHERE a.doctor_id=? GROUP BY u.id ORDER BY last_visit DESC", doctorId);
        }
    }

    static final class RecordService {
        static final Set<String> TYPES = Set.of("Condition", "Allergy", "Medication", "Lab result", "Vaccination", "Surgery", "Vitals", "Other");

        static Reply save(int patientId, String id, String type, String title, String details, String date) {
            if (!TYPES.contains(type)) throw new AppException(400, "Choose a valid record type");
            if (title.isEmpty()) throw new AppException(400, "Title is required");
            String d = date.isEmpty() ? LocalDate.now().toString() : Util.date(date).toString();
            if (id.isEmpty()) {
                Db.ins("INSERT INTO health_records(patient_id,record_type,title,details,record_date) VALUES(?,?,?,?,?)", patientId, type, title, details, d);
                return new Reply("Health record added", null);
            }
            if (Db.run("UPDATE health_records SET record_type=?,title=?,details=?,record_date=? WHERE id=? AND patient_id=?",
                    type, title, details, d, Integer.parseInt(id), patientId) == 0) throw new AppException(404, "Record not found");
            return new Reply("Health record updated", null);
        }

        static Reply delete(int patientId, int id) {
            if (Db.run("DELETE FROM health_records WHERE id=? AND patient_id=?", id, patientId) == 0) throw new AppException(404, "Record not found");
            return new Reply("Health record deleted", null);
        }
    }

    static final class MessageService {
        static boolean related(int a, int b) {
            return Db.q1("SELECT 1 AS x FROM appointments WHERE (patient_id=? AND doctor_id=?) OR (patient_id=? AND doctor_id=?) LIMIT 1", a, b, b, a) != null;
        }

        static List<Map<String, Object>> contacts(Session s) {
            String sql = s.role == Role.PATIENT
                    ? "SELECT DISTINCT u.id,u.name,u.specialization,(SELECT COUNT(*) FROM messages m WHERE m.sender_id=u.id AND m.receiver_id=? AND m.is_read=0) AS unread " +
                      "FROM users u JOIN appointments a ON a.doctor_id=u.id WHERE a.patient_id=?"
                    : "SELECT DISTINCT u.id,u.name,u.specialization,(SELECT COUNT(*) FROM messages m WHERE m.sender_id=u.id AND m.receiver_id=? AND m.is_read=0) AS unread " +
                      "FROM users u JOIN appointments a ON a.patient_id=u.id WHERE a.doctor_id=?";
            return Db.q(sql, s.userId, s.userId);
        }

        static List<Map<String, Object>> thread(int me, int other) {
            if (!related(me, other)) throw new AppException(403, "You can only message people you have an appointment with");
            Db.run("UPDATE messages SET is_read=1 WHERE sender_id=? AND receiver_id=?", other, me);
            return Db.q("SELECT id,sender_id,receiver_id,body,created_at FROM messages WHERE (sender_id=? AND receiver_id=?) OR (sender_id=? AND receiver_id=?) ORDER BY id",
                    me, other, other, me);
        }

        static Reply send(int me, int to, String body) {
            if (body.isEmpty()) throw new AppException(400, "Message cannot be empty");
            if (body.length() > 2000) throw new AppException(400, "Message is too long");
            if (!related(me, to)) throw new AppException(403, "You can only message people you have an appointment with");
            Db.ins("INSERT INTO messages(sender_id,receiver_id,body) VALUES(?,?,?)", me, to, body);
            return new Reply("Message sent", null);
        }
    }

    static final class AnalyticsService {
        static Map<String, Object> summary() {
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> users = new LinkedHashMap<>(Map.of("ADMIN", 0, "DOCTOR", 0, "PATIENT", 0));
            for (Map<String, Object> r : Db.q("SELECT role, COUNT(*) AS n FROM users GROUP BY role")) users.put(Util.s(r.get("role")), r.get("n"));
            Map<String, Object> status = new LinkedHashMap<>();
            for (Status s : Status.values()) status.put(s.name(), 0);
            for (Map<String, Object> r : Db.q("SELECT status, COUNT(*) AS n FROM appointments GROUP BY status")) status.put(Util.s(r.get("status")), r.get("n"));
            LocalDate today = LocalDate.now();
            Map<String, Integer> perDay = new LinkedHashMap<>();
            for (int i = 6; i >= 0; i--) perDay.put(today.minusDays(i).toString(), 0);
            for (Map<String, Object> r : Db.q("SELECT appt_date, COUNT(*) AS n FROM appointments WHERE appt_date>=? GROUP BY appt_date", today.minusDays(6).toString()))
                if (perDay.containsKey(Util.s(r.get("appt_date")))) perDay.put(Util.s(r.get("appt_date")), Util.num(r.get("n")));
            List<Map<String, Object>> daily = new ArrayList<>();
            perDay.forEach((d, n) -> daily.add(new LinkedHashMap<>(Map.of("date", d, "count", n))));
            out.put("users", users);
            out.put("status", status);
            out.put("daily", daily);
            out.put("topDoctors", Db.q("SELECT d.name AS name, COUNT(*) AS count FROM appointments a JOIN users d ON d.id=a.doctor_id " +
                    "WHERE a.status<>'CANCELLED' GROUP BY d.id ORDER BY count DESC LIMIT 5"));
            out.put("consultations", Db.q1("SELECT COUNT(*) AS n FROM consultations").get("n"));
            out.put("records", Db.q1("SELECT COUNT(*) AS n FROM health_records").get("n"));
            return out;
        }
    }

    // =====================================================================
    // 5. WEB LAYER (sessions, request context, routing)
    // =====================================================================

    static final class Session {
        final String token;
        final int userId;
        final Role role;
        volatile String name;
        volatile long lastSeen = System.currentTimeMillis();

        Session(String token, Map<String, Object> user) {
            this.token = token;
            this.userId = Util.num(user.get("id"));
            this.role = Role.parse(Util.s(user.get("role")));
            this.name = Util.s(user.get("name"));
        }
    }

    /** In-memory, thread-safe session store with a 30-minute sliding timeout. */
    static final class Sessions {
        static final ConcurrentHashMap<String, Session> STORE = new ConcurrentHashMap<>();
        static final long TTL_MS = 30 * 60 * 1000L;

        static Session create(Map<String, Object> user) {
            Session s = new Session(Security.randomToken(24), user);
            STORE.put(s.token, s);
            return s;
        }

        static Session get(String token) {
            if (token == null) return null;
            Session s = STORE.get(token);
            if (s == null) return null;
            long now = System.currentTimeMillis();
            if (now - s.lastSeen > TTL_MS) { STORE.remove(token); return null; }
            s.lastSeen = now;
            return s;
        }

        static void sweep() {
            long now = System.currentTimeMillis();
            STORE.values().removeIf(s -> now - s.lastSeen > TTL_MS);
        }
    }

    /** Everything a route needs: merged query/form parameters, session and cookie output. */
    static final class Ctx {
        final Map<String, String> params = new HashMap<>();
        Session session;
        String setCookie;

        static Ctx from(HttpExchange ex) throws IOException {
            Ctx c = new Ctx();
            c.addEncoded(ex.getRequestURI().getRawQuery());
            if ("POST".equals(ex.getRequestMethod()))
                c.addEncoded(new String(ex.getRequestBody().readNBytes(256 * 1024), StandardCharsets.UTF_8));
            String cookies = ex.getRequestHeaders().getFirst("Cookie");
            if (cookies != null)
                for (String part : cookies.split(";"))
                    if (part.trim().startsWith("HCSESSION=")) c.session = Sessions.get(part.trim().substring(10));
            return c;
        }

        private void addEncoded(String s) {
            if (s == null || s.isEmpty()) return;
            for (String kv : s.split("&")) {
                int i = kv.indexOf('=');
                String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
                String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
                params.put(k, v);
            }
        }

        String str(String k) { return params.getOrDefault(k, "").trim(); }

        String req(String k) {
            String v = str(k);
            if (v.isEmpty()) throw new AppException(400, k + " is required");
            return v;
        }

        int integer(String k) {
            try { return Integer.parseInt(req(k)); }
            catch (NumberFormatException e) { throw new AppException(400, k + " must be a number"); }
        }
    }

    interface Route { Object handle(Ctx c) throws Exception; }

    record RouteDef(Set<Role> roles, Route route) {}   // roles == null -> public endpoint

    static final Map<String, RouteDef> ROUTES = new HashMap<>();
    static final Set<Role> ANY = EnumSet.allOf(Role.class);

    static Set<Role> only(Role... r) { return EnumSet.copyOf(Arrays.asList(r)); }
    static void get(String path, Set<Role> roles, Route r) { ROUTES.put("GET " + path, new RouteDef(roles, r)); }
    static void post(String path, Set<Role> roles, Route r) { ROUTES.put("POST " + path, new RouteDef(roles, r)); }

    static void registerRoutes() {
        Set<Role> admin = only(Role.ADMIN), doctor = only(Role.DOCTOR), patient = only(Role.PATIENT);

        // ---- public & session ----
        get("/api/public", null, c -> Map.of("clinicName", Settings.get("clinic_name"),
                "allowRegistration", Settings.get("allow_registration").equals("true")));

        post("/api/login", null, c -> {
            Map<String, Object> u = UserService.login(c.req("email"), c.req("password"));
            Session s = Sessions.create(u);
            c.setCookie = "HCSESSION=" + s.token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=1800";
            return new Reply("Welcome back, " + u.get("name"), u);
        });

        post("/api/register", null, c -> {
            if (!Settings.get("allow_registration").equals("true")) throw new AppException(403, "Registration is currently closed");
            UserService.create(c.req("name"), c.req("email"), c.req("password"), Role.PATIENT, "", c.str("phone"));
            return new Reply("Account created. You can sign in now.", null);
        });

        post("/api/logout", ANY, c -> {
            Sessions.STORE.remove(c.session.token);
            c.setCookie = "HCSESSION=; Path=/; HttpOnly; Max-Age=0";
            return new Reply("Signed out", null);
        });

        get("/api/me", ANY, c -> Db.q1("SELECT " + UserService.COLS + " FROM users WHERE id=?", c.session.userId));

        post("/api/profile", ANY, c -> {
            UserService.updateProfile(c.session.userId, c.req("name"), c.str("phone"), c.str("password"));
            c.session.name = c.str("name");
            return new Reply("Profile updated successfully", null);
        });

        get("/api/notifications", ANY, c -> Db.q("SELECT id,text,seen,created_at FROM notifications WHERE user_id=? ORDER BY id DESC LIMIT 30", c.session.userId));
        post("/api/notifications/read", ANY, c -> { Db.run("UPDATE notifications SET seen=1 WHERE user_id=?", c.session.userId); return new Reply("", null); });

        // ---- shared booking data ----
        get("/api/doctors", ANY, c -> Db.q("SELECT id,name,specialization,email FROM users WHERE role='DOCTOR' AND active=1 ORDER BY name"));
        get("/api/slots", ANY, c -> Db.read(conn -> Slots.free(conn, c.integer("doctorId"), Util.date(c.req("date")))));
        get("/api/appointments", ANY, c -> AppointmentService.list(c.session));
        post("/api/appointments/cancel", ANY, c -> AppointmentService.cancel(c.session, c.integer("id")));
        post("/api/appointments/book", only(Role.PATIENT, Role.ADMIN), c -> {
            int patientId = c.session.role == Role.PATIENT ? c.session.userId : c.integer("patientId");
            return AppointmentService.book(patientId, c.integer("doctorId"), c.req("date"), c.req("time"), c.str("reason"));
        });

        // ---- admin ----
        get("/api/admin/users", admin, c -> Db.q("SELECT " + UserService.COLS + " FROM users ORDER BY role, name"));
        post("/api/admin/users/save", admin, c -> {
            Role role = Role.parse(c.req("role"));
            if (c.str("id").isEmpty()) {
                UserService.create(c.req("name"), c.req("email"), c.str("password"), role, c.str("specialization"), c.str("phone"));
                return new Reply("User created successfully", null);
            }
            int id = c.integer("id");
            if (id == c.session.userId && (role != Role.ADMIN || c.str("active").equals("0")))
                throw new AppException(409, "You cannot demote or deactivate yourself");
            UserService.update(id, c.req("name"), c.req("email"), c.str("password"), role, c.str("specialization"), c.str("phone"), !c.str("active").equals("0"));
            return new Reply("User updated successfully", null);
        });
        post("/api/admin/users/delete", admin, c -> { UserService.delete(c.integer("id"), c.session.userId); return new Reply("User deleted successfully", null); });
        post("/api/admin/appointments/status", admin, c -> AppointmentService.setStatus(c.integer("id"), c.req("status")));
        post("/api/admin/appointments/delete", admin, c -> AppointmentService.delete(c.integer("id")));
        get("/api/admin/settings", admin, c -> Settings.all());
        post("/api/admin/settings", admin, c -> Settings.update(c.params));
        get("/api/admin/analytics", admin, c -> AnalyticsService.summary());

        // ---- healthcare professional ----
        get("/api/doctor/schedule", doctor, c -> ScheduleService.forDoctor(c.session.userId));
        post("/api/doctor/schedule/save", doctor, c ->
                ScheduleService.save(c.session.userId, c.integer("weekday"), c.req("start"), c.req("end"), c.integer("slot")));
        post("/api/doctor/schedule/off", doctor, c -> ScheduleService.dayOff(c.session.userId, c.integer("weekday")));
        post("/api/doctor/consult", doctor, c ->
                ConsultationService.provide(c.session.userId, c.integer("appointmentId"), c.str("diagnosis"), c.req("advice"), c.str("prescription")));
        get("/api/doctor/patients", doctor, c -> ConsultationService.myPatients(c.session.userId));
        get("/api/doctor/patient", doctor, c -> ConsultationService.patientFile(c.session.userId, c.integer("patientId")));

        // ---- patient ----
        get("/api/patient/records", patient, c -> Db.q("SELECT id,record_type,title,details,record_date FROM health_records WHERE patient_id=? ORDER BY record_date DESC, id DESC", c.session.userId));
        post("/api/patient/records/save", patient, c ->
                RecordService.save(c.session.userId, c.str("id"), c.str("type"), c.str("title"), c.str("details"), c.str("date")));
        post("/api/patient/records/delete", patient, c -> RecordService.delete(c.session.userId, c.integer("id")));

        // ---- messaging (doctor <-> patient) ----
        get("/api/messages/contacts", only(Role.DOCTOR, Role.PATIENT), c -> MessageService.contacts(c.session));
        get("/api/messages/thread", only(Role.DOCTOR, Role.PATIENT), c -> MessageService.thread(c.session.userId, c.integer("userId")));
        post("/api/messages/send", only(Role.DOCTOR, Role.PATIENT), c -> MessageService.send(c.session.userId, c.integer("to"), c.str("body")));
    }

    static void handleApi(HttpExchange ex) throws IOException {
        int status = 200;
        Map<String, Object> body = new LinkedHashMap<>();
        String cookie = null;
        try {
            Ctx c = Ctx.from(ex);
            RouteDef rd = ROUTES.get(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            if (rd == null) throw new AppException(404, "Unknown endpoint");
            if (rd.roles() != null) {
                if (c.session == null) throw new AppException(401, "Your session expired. Please sign in again.");
                if (!rd.roles().contains(c.session.role)) throw new AppException(403, "You do not have access to this action");
            }
            Object result = rd.route().handle(c);
            body.put("ok", true);
            if (result instanceof Reply r) { body.put("message", r.message()); body.put("data", r.data()); }
            else body.put("data", result);
            cookie = c.setCookie;
        } catch (AppException e) {
            status = e.status;
            body.put("ok", false);
            body.put("message", e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Unhandled error", e);
            status = 500;
            body.put("ok", false);
            body.put("message", "Something went wrong on the server");
        }
        if (cookie != null) ex.getResponseHeaders().add("Set-Cookie", cookie);
        send(ex, status, "application/json; charset=utf-8", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int status, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.sendResponseHeaders(status, data.length);
        ex.getResponseBody().write(data);
        ex.close();
    }

    // =====================================================================
    // 6. BACKGROUND THREADS + SEED DATA + MAIN
    // =====================================================================

    /** Runs on its own thread: creates reminders 24h before each scheduled appointment. */
    static void sendReminders() {
        try {
            LocalDateTime now = LocalDateTime.now(), horizon = now.plusHours(24);
            List<Map<String, Object>> rows = Db.q(
                    "SELECT a.id,a.patient_id,a.doctor_id,a.appt_date,a.appt_time,d.name AS dn,p.name AS pn " +
                    "FROM appointments a JOIN users d ON d.id=a.doctor_id JOIN users p ON p.id=a.patient_id " +
                    "WHERE a.status='SCHEDULED' AND a.reminded=0 AND a.appt_date BETWEEN ? AND ?",
                    now.toLocalDate().toString(), horizon.toLocalDate().toString());
            for (Map<String, Object> r : rows) {
                LocalDateTime at = LocalDateTime.of(LocalDate.parse(Util.s(r.get("appt_date"))), LocalTime.parse(Util.s(r.get("appt_time"))));
                if (!at.isAfter(now) || at.isAfter(horizon)) continue;
                final int id = Util.num(r.get("id")), pid = Util.num(r.get("patient_id")), did = Util.num(r.get("doctor_id"));
                final String when = r.get("appt_date") + " at " + r.get("appt_time");
                final String dn = Util.s(r.get("dn")), pn = Util.s(r.get("pn"));
                Db.tx(c -> {
                    Notifications.add(c, pid, "Reminder: consultation with " + dn + " on " + when);
                    Notifications.add(c, did, "Reminder: consultation with " + pn + " on " + when);
                    Db.run(c, "UPDATE appointments SET reminded=1 WHERE id=?", id);
                    return null;
                });
                LOG.info("Reminder sent for appointment #" + id);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Reminder job failed", e);
        }
    }

    static void seedIfEmpty() {
        if (Util.num(Db.q1("SELECT COUNT(*) AS n FROM users").get("n")) > 0) return;
        LOG.info("Empty database - creating demo data");
        UserService.create("System Admin", "admin@careconnect.com", "admin123", Role.ADMIN, "", "");
        long d1 = UserService.create("Dr. Ananya Sharma", "dr.sharma@careconnect.com", "doctor123", Role.DOCTOR, "Cardiologist", "9810000001");
        long d2 = UserService.create("Dr. Rohan Iyer", "dr.iyer@careconnect.com", "doctor123", Role.DOCTOR, "Dermatologist", "9810000002");
        long d3 = UserService.create("Dr. Sana Khan", "dr.khan@careconnect.com", "doctor123", Role.DOCTOR, "General Physician", "9810000003");
        long p1 = UserService.create("Aarav Mehta", "patient@careconnect.com", "patient123", Role.PATIENT, "", "9820000001");
        for (long d : new long[]{d1, d2, d3})
            for (int w = 1; w <= 5; w++) ScheduleService.save((int) d, w, "09:00", "17:00", 30);
        ScheduleService.save((int) d3, 6, "10:00", "14:00", 20);
        Db.tx(c -> {
            long appt = Db.insert(c, "INSERT INTO appointments(patient_id,doctor_id,appt_date,appt_time,reason,status,reminded) VALUES(?,?,?,?,?,'COMPLETED',1)",
                    p1, d3, LocalDate.now().minusDays(9).toString(), "10:30", "Recurring headaches and fatigue");
            Db.insert(c, "INSERT INTO consultations(appointment_id,diagnosis,advice,prescription) VALUES(?,?,?,?)", appt,
                    "Tension-type headache with mild dehydration",
                    "Drink 2.5-3 litres of water daily, keep a regular sleep schedule and limit screen time after 10 pm. Review again if headaches continue beyond two weeks.",
                    "Paracetamol 500 mg, only when needed (max 3 per day)");
            Db.insert(c, "INSERT INTO health_records(patient_id,record_type,title,details,record_date) VALUES(?,?,?,?,?)", p1, "Allergy", "Penicillin allergy", "Rash and itching reported in childhood.", "2022-03-14");
            Db.insert(c, "INSERT INTO health_records(patient_id,record_type,title,details,record_date) VALUES(?,?,?,?,?)", p1, "Vaccination", "Influenza vaccine", "Annual flu shot, left arm.", LocalDate.now().minusMonths(2).toString());
            return null;
        });
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        try {
            Db.init();
        } catch (SQLException e) {
            System.err.println("Could not open the database: " + e.getMessage());
            System.err.println("Make sure the SQLite JDBC jar is in ./lib and on the classpath (see README).");
            return;
        }
        seedIfEmpty();
        registerRoutes();

        ScheduledExecutorService background = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "careconnect-bg");
            t.setDaemon(true);
            return t;
        });
        background.scheduleWithFixedDelay(HealthConsultPlatform::sendReminders, 5, 60, TimeUnit.SECONDS);
        background.scheduleWithFixedDelay(Sessions::sweep, 1, 1, TimeUnit.MINUTES);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", HealthConsultPlatform::handleApi);
        server.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            if (p.equals("/") || p.equals("/index.html"))
                send(ex, 200, "text/html; charset=utf-8", UI.INDEX_HTML.getBytes(StandardCharsets.UTF_8));
            else
                send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
        });
        ExecutorService workers = Executors.newFixedThreadPool(16);
        server.setExecutor(workers);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            workers.shutdown();
            background.shutdown();
            Db.closeAll();
        }));
        server.start();
        System.out.println("\n  CareConnect is running ->  http://localhost:" + port + "\n");
        System.out.println("  Demo logins:  admin@careconnect.com / admin123");
        System.out.println("                dr.sharma@careconnect.com / doctor123");
        System.out.println("                patient@careconnect.com / patient123\n");
    }


    // =====================================================================
    // 7. USER INTERFACE (single-page app served at "/")
    //    Split in two text blocks (class-file constant size limit) and joined at runtime.
    // =====================================================================
    static final class UI {
        static final String HEAD = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>CareConnect - Online Health Consultation</title>
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='9' fill='%230E5A4C'/%3E%3Cpath d='M13 7h6v6h6v6h-6v6h-6v-6H7v-6h6z' fill='%23fff'/%3E%3C/svg%3E">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:wght@600;700;800&family=Figtree:wght@400;500;600;700&display=swap" rel="stylesheet">
<style>
:root{--paper:#EDF1EC;--surface:#FBFCFA;--ink:#10231F;--muted:#5B6F69;--pine:#0E5A4C;--pine-d:#0A3F35;--mint:#CFE5DA;--coral:#E8553D;--line:#CBD6CE;--r:14px}
*{box-sizing:border-box}
html{scroll-behavior:smooth}
body{margin:0;background:var(--paper);color:var(--ink);font:15px/1.55 Figtree,system-ui,"Segoe UI",sans-serif}
h1,h2,h3{font-family:"Bricolage Grotesque",Figtree,system-ui,sans-serif;letter-spacing:-.02em;margin:0;line-height:1.15}
h3{font-size:18px;margin-bottom:14px}
button{font:inherit;color:inherit}
:focus-visible{outline:3px solid var(--coral);outline-offset:2px}
[hidden]{display:none!important}
.muted{color:var(--muted)}
.err{color:#A3321F}
.lede{color:var(--muted);margin:0 0 18px;max-width:62ch}

.btn{background:var(--pine);color:#fff;border:1px solid var(--pine);border-radius:999px;padding:.6em 1.25em;font-weight:600;cursor:pointer;transition:background .15s}
.btn:hover{background:var(--pine-d)}
.btn.ghost{background:transparent;color:var(--ink);border-color:var(--line)}
.btn.ghost:hover{background:#fff;border-color:var(--pine)}
.btn.danger{background:transparent;color:#A3321F;border-color:#E3B7AF}
.btn.danger:hover{background:#F6DDD8}
.btn.sm{padding:.3em .9em;font-size:13px}
.btn.block{width:100%}

label{display:block;font-weight:600;font-size:13px;margin-top:12px}
label>input,label>select,label>textarea,.in{display:block;width:100%;margin-top:4px;padding:.62em .8em;border:1px solid var(--line);border-radius:10px;background:#fff;font:400 15px Figtree,system-ui,sans-serif;color:var(--ink)}
textarea{resize:vertical}
.row2{display:grid;grid-template-columns:1fr 1fr;gap:12px}

.panel{background:var(--surface);border:1px solid var(--line);border-radius:var(--r);padding:20px}
.grid2{display:grid;grid-template-columns:1.4fr 1fr;gap:16px;margin-top:16px}
.split{display:grid;grid-template-columns:minmax(260px,340px) 1fr;gap:16px;align-items:start}
.bar-head{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:16px;flex-wrap:wrap}
.bar-head input{max-width:280px}
.scroll{overflow-x:auto}
.tbl{width:100%;border-collapse:collapse}
.tbl th{text-align:left;font-size:13px;color:var(--muted);font-weight:600;padding:8px 10px;border-bottom:1px solid var(--line);white-space:nowrap}
.tbl td{padding:12px 10px;border-bottom:1px solid var(--line);vertical-align:middle}
.tbl tr:last-child td{border-bottom:0}
.acts{display:flex;gap:6px;justify-content:flex-end;flex-wrap:wrap}
.pill{display:inline-block;padding:.1em .7em;border-radius:999px;font-size:12px;font-weight:700;background:var(--mint);color:var(--pine-d)}
.pill.SCHEDULED{background:#FFF0CF;color:#7C5200}
.pill.COMPLETED{background:#D5EFE2;color:#14663F}
.pill.CANCELLED{background:#F6DDD8;color:#A3321F}
.pill.off{background:#E4E8E5;color:#55645F}

/* auth */
.auth{display:grid;grid-template-columns:1.15fr 1fr;min-height:100vh}
.art{background:var(--pine-d);color:#fff;padding:56px 56px 40px;display:flex;flex-direction:column;justify-content:space-between;overflow:hidden}
.brand{display:flex;align-items:center;gap:10px;font:700 20px "Bricolage Grotesque",sans-serif}
.logo{width:34px;height:34px;border-radius:10px;background:var(--coral);position:relative;flex:none}
.logo:before,.logo:after{content:"";position:absolute;background:#fff;border-radius:2px}
.logo:before{left:14px;top:7px;width:6px;height:20px}
.logo:after{left:7px;top:14px;width:20px;height:6px}
.art h1{font-size:clamp(34px,4.6vw,58px);font-weight:800;max-width:14ch}
.art p{max-width:42ch;color:#BFDCD0;font-size:17px;margin:18px 0 0}
.ecg{width:100%;height:auto;margin:28px 0}
.ecg path{fill:none;stroke:#7FD1B0;stroke-width:3;stroke-linecap:round;stroke-linejoin:round;stroke-dasharray:1500;stroke-dashoffset:1500;animation:draw 2.8s ease-out .2s forwards}
.ecg circle{fill:var(--coral)}
@keyframes draw{to{stroke-dashoffset:0}}
.facts{display:flex;gap:28px;flex-wrap:wrap;color:#BFDCD0;font-size:14px}
.facts b{display:block;color:#fff;font-size:15px}
.card{display:flex;align-items:center;justify-content:center;padding:32px}
.card>div{width:min(400px,100%)}
.card h2{font-size:30px;margin-bottom:6px}
.tabs{display:flex;gap:4px;background:#E1E8E3;border-radius:999px;padding:4px;margin:20px 0 6px}
.tabs button{flex:1;border:0;background:transparent;border-radius:999px;padding:.5em;font-weight:600;cursor:pointer;color:var(--muted)}
.tabs button.on{background:#fff;color:var(--ink);box-shadow:0 1px 3px rgba(16,35,31,.15)}
.demo{margin-top:22px;padding-top:16px;border-top:1px dashed var(--line);font-size:13px;color:var(--muted)}
.demo div{display:flex;gap:8px;margin-top:8px;flex-wrap:wrap}
.note-top{background:#FFF0CF;color:#7C5200;border-radius:10px;padding:10px 12px;margin-top:14px;font-size:14px}

/* shell */
.shell{display:grid;grid-template-columns:250px 1fr;min-height:100vh}
aside{background:var(--pine-d);color:#fff;padding:22px 16px;display:flex;flex-direction:column;gap:22px;position:sticky;top:0;height:100vh}
.nav{display:flex;flex-direction:column;gap:2px}
.nav button{background:transparent;border:0;color:#CFE5DA;text-align:left;padding:.65em .9em;border-radius:10px;cursor:pointer;font-weight:500}
.nav button:hover{background:rgba(255,255,255,.08)}
.nav button.on{background:var(--mint);color:var(--pine-d);font-weight:700}
.me{margin-top:auto;font-size:13px;color:#BFDCD0;border-top:1px solid rgba(255,255,255,.14);padding-top:14px}
.me b{display:block;color:#fff;font-size:15px}
.me button{margin-top:10px;background:transparent;border:1px solid rgba(255,255,255,.3);color:#fff;border-radius:999px;padding:.3em 1em;cursor:pointer}
main{padding:26px 32px 48px;min-width:0}
.top{display:flex;justify-content:space-between;align-items:center;margin-bottom:22px;gap:12px}
.top h2{font-size:30px}
.bell{position:relative;background:#fff;border:1px solid var(--line);border-radius:50%;width:42px;height:42px;cursor:pointer;font-size:18px}
.bell i{position:absolute;top:8px;right:9px;width:9px;height:9px;background:var(--coral);border-radius:50%}
#view{max-width:1100px}

.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:14px}
.stat{background:var(--surface);border:1px solid var(--line);border-radius:var(--r);padding:16px 18px}
.stat b{display:block;font:800 36px "Bricolage Grotesque",sans-serif;color:var(--pine)}
.stat span{color:var(--muted);font-size:14px}
.bars{display:flex;gap:10px;align-items:flex-end;height:190px}
.bar{flex:1;height:100%;display:flex;flex-direction:column;justify-content:flex-end;align-items:center;gap:4px;font-size:12px;color:var(--muted)}
.bar i{display:block;width:100%;background:var(--pine);border-radius:8px 8px 2px 2px;min-height:4px}
.bar b{color:var(--ink)}
.stack{display:flex;height:14px;border-radius:99px;overflow:hidden;background:#E1E8E3;margin:6px 0 12px}
.legend{display:flex;gap:14px;flex-wrap:wrap;font-size:13px}
.legend i{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:6px}
.rank{list-style:none;margin:0;padding:0}
.rank li{display:flex;justify-content:space-between;padding:9px 0;border-bottom:1px solid var(--line)}
.rank li:last-child{border:0}

.docs{display:grid;gap:10px}
.doc{display:grid;grid-template-columns:44px 1fr;column-gap:12px;align-items:center;text-align:left;padding:12px;border:1px solid var(--line);border-radius:12px;background:#fff;cursor:pointer}
.doc .av{grid-row:span 2}
.doc small{color:var(--muted)}
.doc.on{border-color:var(--pine);box-shadow:inset 0 0 0 2px var(--pine)}
.av{width:44px;height:44px;border-radius:50%;background:var(--mint);color:var(--pine-d);display:grid;place-items:center;font-weight:700;flex:none}
.chips{display:flex;flex-wrap:wrap;gap:8px;margin:14px 0 4px}
.chip{border:1px solid var(--line);background:#fff;border-radius:999px;padding:.4em 1em;cursor:pointer;font-weight:500}
.chip.on{background:var(--pine);border-color:var(--pine);color:#fff}

.appt{display:grid;grid-template-columns:64px 1fr auto;gap:16px;align-items:center;padding:14px 0;border-bottom:1px solid var(--line)}
.appt:last-child{border:0}
.date{text-align:center;background:var(--mint);border-radius:12px;padding:6px 0;line-height:1.1;color:var(--pine-d)}
.date b{display:block;font:800 24px "Bricolage Grotesque",sans-serif}
.date small{font-weight:600}
.appt h4{margin:0;font-size:16px}
.appt p{margin:2px 0 0;color:var(--muted);font-size:14px}
.advice{background:#fff;border-left:4px solid var(--pine);border-radius:0 10px 10px 0;padding:10px 14px;margin-top:8px;font-size:14px}
.advice b{color:var(--pine-d)}
.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:14px}
.rec{background:var(--surface);border:1px solid var(--line);border-radius:var(--r);padding:16px}
.rec h4{margin:8px 0 4px;font-size:16px}
.rec p{margin:0 0 10px;color:var(--muted);font-size:14px;white-space:pre-wrap}

.sched{display:grid;gap:10px}
.day{display:grid;grid-template-columns:110px 130px 130px 110px auto auto;gap:10px;align-items:center;background:var(--surface);border:1px solid var(--line);border-radius:12px;padding:10px 14px}
.day.off{background:transparent;border-style:dashed}
.day .in{margin:0}

.chat{display:grid;grid-template-columns:270px 1fr;gap:16px}
.ct{display:flex;gap:10px;align-items:center;width:100%;text-align:left;border:0;background:transparent;padding:8px;border-radius:10px;cursor:pointer}
.ct:hover,.ct.on{background:#E3EBE6}
.ct small{display:block;color:var(--muted)}
.badge{margin-left:auto;background:var(--coral);color:#fff;border-radius:99px;font-size:12px;padding:0 7px;font-style:normal}
.msgs{height:46vh;overflow:auto;display:flex;flex-direction:column;gap:8px;padding:4px 2px 12px}
.m{max-width:78%;padding:8px 13px;border-radius:14px;background:#E3EBE6;white-space:pre-wrap;word-break:break-word}
.m small{display:block;font-size:11px;opacity:.65}
.m.me{align-self:flex-end;background:var(--pine);color:#fff}
.send{display:flex;gap:8px}
.send .in{margin:0}
.note{padding:10px 0;border-bottom:1px solid var(--line)}
.note.new{font-weight:600}
.note small{display:block;color:var(--muted);font-weight:400}
.sec{margin:18px 0 8px;font:700 15px "Bricolage Grotesque",sans-serif}

dialog{border:0;border-radius:18px;padding:0;width:min(560px,94vw);box-shadow:0 24px 70px rgba(10,63,53,.4);color:var(--ink)}
dialog.wide{width:min(760px,95vw)}
dialog::backdrop{background:rgba(10,63,53,.5)}
.mform{padding:24px}
.mform h3{font-size:22px}
.mbody{max-height:64vh;overflow:auto;padding:2px 4px 2px 2px}
.mfoot{display:flex;justify-content:flex-end;gap:10px;margin-top:18px}
#toasts{position:fixed;right:16px;bottom:16px;display:grid;gap:8px;z-index:99}
.toast{background:var(--ink);color:#fff;padding:12px 16px;border-radius:12px;max-width:340px;box-shadow:0 8px 24px rgba(0,0,0,.25);animation:in .2s ease-out}
.toast.bad{background:#A3321F}
@keyframes in{from{transform:translateY(8px);opacity:0}}

@media(max-width:900px){
 .auth{grid-template-columns:1fr}.art{padding:28px}.art h1{max-width:none}
 .shell{grid-template-columns:1fr}
 aside{position:static;height:auto;flex-direction:row;align-items:center;overflow-x:auto;padding:12px;gap:12px}
 aside .brand span{display:none}
 .nav{flex-direction:row}.nav button{white-space:nowrap}
 .me{display:none}
 main{padding:18px 16px 40px}
 .grid2,.split,.chat{grid-template-columns:1fr}
 .day{grid-template-columns:1fr 1fr}.day b{grid-column:1/-1}
 .row2{grid-template-columns:1fr}
}
@media(prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}.ecg path{stroke-dashoffset:0}}
</style>
</head>
<body>
<div id="app"></div>
<div id="toasts" role="status" aria-live="polite"></div>
<script>
""";

        static final String SCRIPT = """
const $=(s,e=document)=>e.querySelector(s);
const esc=s=>String(s==null?'':s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const qs=o=>new URLSearchParams(o).toString();
const S={me:null,pub:{},view:null,timer:null,pick:{}};
const DAYS=['','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'];
const MONTHS=['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
const today=()=>{const d=new Date();return new Date(d-d.getTimezoneOffset()*60000).toISOString().slice(0,10)};
const fd=d=>new Date(d+'T00:00').toLocaleDateString(undefined,{weekday:'short',day:'numeric',month:'short',year:'numeric'});
const ft=t=>{const [h,m]=t.split(':').map(Number);return ((h+11)%12+1)+':'+String(m).padStart(2,'0')+' '+(h<12?'AM':'PM')};
const initials=n=>n.replace('Dr. ','').split(' ').map(x=>x[0]).slice(0,2).join('').toUpperCase();

async function api(path,data){
  const o=data===undefined?{}:{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(data)};
  const r=await fetch('/api/'+path,o);
  const j=await r.json().catch(()=>({ok:false,message:'Unexpected server response'}));
  if(!j.ok){
    if(r.status===401&&S.me){S.me=null;showLogin('Your session expired. Please sign in again.')}
    throw new Error(j.message);
  }
  return j;
}
function toast(msg,bad){
  if(!msg)return;
  const t=document.createElement('div');t.className='toast'+(bad?' bad':'');t.textContent=msg;
  $('#toasts').append(t);setTimeout(()=>t.remove(),3800);
}
async function safe(fn){try{return await fn()}catch(e){toast(e.message,true)}}
const view=h=>{$('#view').innerHTML=h};

function modal(title,html,onSubmit,ok,wide){
  const d=document.createElement('dialog');if(wide)d.className='wide';
  d.innerHTML=`<form class="mform"><h3>${esc(title)}</h3><div class="mbody">${html}</div><div class="mfoot"><button type="button" class="btn ghost" data-x>${onSubmit?'Cancel':'Close'}</button>${onSubmit?`<button class="btn" type="submit">${ok||'Save'}</button>`:''}</div></form>`;
  document.body.append(d);d.showModal();
  d.addEventListener('close',()=>d.remove());
  $('[data-x]',d).onclick=()=>d.close();
  $('form',d).onsubmit=async e=>{
    e.preventDefault();if(!onSubmit)return d.close();
    try{await onSubmit(Object.fromEntries(new FormData(e.target)));d.close()}catch(err){toast(err.message,true)}
  };
  return d;
}

/* ---------- sign in / register ---------- */
function showLogin(note){
  clearInterval(S.timer);
  $('#app').innerHTML=`<div class="auth">
  <section class="art"><div class="brand"><span class="logo"></span><span>${esc(S.pub.clinicName||'CareConnect')}</span></div>
   <div><h1>See a doctor without leaving home.</h1>
   <svg class="ecg" viewBox="0 0 640 120" aria-hidden="true"><path d="M0 70 H150 L170 70 L186 28 L210 108 L228 14 L246 70 H330 L346 56 L362 70 H640"/><circle cx="636" cy="70" r="5"/></svg>
   <p>Book a consultation, keep your health records in one place and read your doctor's advice whenever you need it.</p></div>
   <div class="facts"><span><b>Pick your slot</b>Real availability, no phone calls</span><span><b>Written advice</b>Saved to your account</span><span><b>Private records</b>Only you and your doctor</span></div></section>
  <section class="card"><div>
   <h2 id="lt">Welcome back</h2><span class="muted">Sign in to continue to your dashboard.</span>
   ${note?`<div class="note-top">${esc(note)}</div>`:''}
   <div class="tabs"><button class="on" id="t1" onclick="tab(1)">Sign in</button>${S.pub.allowRegistration?'<button id="t2" onclick="tab(2)">Create account</button>':''}</div>
   <form id="f1" onsubmit="doLogin(event)"><label>Email<input name="email" type="email" required autocomplete="username"></label>
    <label>Password<input name="password" type="password" required autocomplete="current-password"></label>
    <p><button class="btn block">Sign in</button></p></form>
   <form id="f2" hidden onsubmit="doRegister(event)"><label>Full name<input name="name" required minlength="2"></label>
    <label>Email<input name="email" type="email" required></label>
    <div class="row2"><label>Phone<input name="phone" inputmode="tel"></label><label>Password<input name="password" type="password" minlength="6" required autocomplete="new-password"></label></div>
    <p><button class="btn block">Create patient account</button></p></form>
   <div class="demo">Demo accounts, tap to fill:<div>
    <button class="btn ghost sm" onclick="demo('admin@careconnect.com','admin123')">Admin</button>
    <button class="btn ghost sm" onclick="demo('dr.sharma@careconnect.com','doctor123')">Doctor</button>
    <button class="btn ghost sm" onclick="demo('patient@careconnect.com','patient123')">Patient</button></div></div>
  </div></section></div>`;
}
function tab(n){
  $('#f1').hidden=n!==1;$('#f2').hidden=n!==2;$('#t1').classList.toggle('on',n===1);
  if($('#t2'))$('#t2').classList.toggle('on',n===2);
  $('#lt').textContent=n===1?'Welcome back':'Create your account';
}
function demo(e,p){tab(1);const f=$('#f1');f.email.value=e;f.password.value=p;f.password.focus()}
async function doLogin(ev){
  ev.preventDefault();
  await safe(async()=>{
    const r=await api('login',Object.fromEntries(new FormData(ev.target)));
    S.me=r.data;toast(r.message);showApp();
  });
}
async function doRegister(ev){
  ev.preventDefault();
  await safe(async()=>{
    const r=await api('register',Object.fromEntries(new FormData(ev.target)));
    toast(r.message);tab(1);
  });
}
async function logout(){await safe(async()=>{await api('logout',{});S.me=null;showLogin()})}

/* ---------- application shell ---------- */
const NAV={
 ADMIN:[['overview','Analytics'],['users','Users'],['appts','Appointments'],['settings','System settings'],['profile','Profile']],
 DOCTOR:[['consults','Consultations'],['schedule','My schedule'],['patients','Patient records'],['messages','Messages'],['profile','Profile']],
 PATIENT:[['book','Book a consultation'],['visits','My appointments'],['records','Health records'],['advice','Medical advice'],['messages','Messages'],['profile','Profile']]
};
const TITLES={overview:'System analytics',users:'User management',appts:'Appointment management',settings:'System settings',consults:'Consultations',schedule:'My schedule',patients:'Patient records',messages:'Messages',profile:'Profile',book:'Book a consultation',visits:'My appointments',records:'Health records',advice:'Medical advice'};
function showApp(){
  const r=S.me.role;
  $('#app').innerHTML=`<div class="shell"><aside><div class="brand"><span class="logo"></span><span>${esc(S.pub.clinicName||'CareConnect')}</span></div>
   <nav class="nav" aria-label="Main">${NAV[r].map(n=>`<button data-v="${n[0]}" onclick="go('${n[0]}')">${n[1]}</button>`).join('')}</nav>
   <div class="me"><b>${esc(S.me.name)}</b>${r==='DOCTOR'?esc(S.me.specialization||'Healthcare professional'):r==='ADMIN'?'Administrator':'Patient'}</div></aside>
   <main><div class="top"><h2 id="title"></h2><div style="display:flex;gap:10px;align-items:center"><button class="bell" onclick="bell()" aria-label="Notifications">&#128276;<i id="dot" hidden></i></button><button class="btn ghost sm" onclick="logout()">Sign out</button></div></div><div id="view"></div></main></div>`;
  go(NAV[r][0][0]);refreshBell();
  clearInterval(S.bellTimer);S.bellTimer=setInterval(refreshBell,30000);
}
function go(v){
  S.view=v;clearInterval(S.timer);
  document.querySelectorAll('.nav button').forEach(b=>b.classList.toggle('on',b.dataset.v===v));
  $('#title').textContent=TITLES[v];view('<p class="muted">Loading...</p>');
  VIEWS[v]().catch(e=>view('<p class="err">'+esc(e.message)+'</p>'));
}
async function refreshBell(){try{const r=await api('notifications');$('#dot').hidden=!r.data.some(n=>!n.seen)}catch(e){}}
async function bell(){
  await safe(async()=>{
    const r=(await api('notifications')).data;
    modal('Notifications',r.length?r.map(n=>`<div class="note ${n.seen?'':'new'}">${esc(n.text)}<small>${esc(n.created_at)}</small></div>`).join(''):'<p class="muted">Nothing here yet.</p>');
    await api('notifications/read',{});$('#dot').hidden=true;
  });
}

/* ---------- shared pieces ---------- */
function apptRow(a,actions){
  const d=new Date(a.appt_date+'T00:00');
  const who=S.me.role==='PATIENT'?esc(a.doctor_name)+' <span class="muted">('+esc(a.specialization||'General')+')</span>':esc(a.patient_name);
  return `<div class="appt"><div class="date"><b>${d.getDate()}</b><small>${MONTHS[d.getMonth()]}</small></div>
   <div><h4>${who} <span class="pill ${a.status}">${a.status.toLowerCase()}</span></h4><p>${fd(a.appt_date)} at ${ft(a.appt_time)}${a.reason?' - '+esc(a.reason):''}</p>${adviceBlock(a)}</div>
   <div class="acts">${actions||''}</div></div>`;
}
function adviceBlock(a){
  if(!a.consult_id)return '';
  return `<div class="advice">${a.diagnosis?'<b>Diagnosis:</b> '+esc(a.diagnosis)+'<br>':''}<b>Advice:</b> ${esc(a.advice)}${a.prescription?'<br><b>Prescription:</b> '+esc(a.prescription):''}</div>`;
}
async function loadSlots(f){
  const sel=f.time;
  if(!f.doctorId.value||!f.date.value){sel.innerHTML='<option value="">Choose doctor and date</option>';return}
  const r=await api('slots?'+qs({doctorId:f.doctorId.value,date:f.date.value}));
  sel.innerHTML=r.data.length?r.data.map(t=>`<option value="${t}">${ft(t)}</option>`).join(''):'<option value="">No free slots</option>';
}
const VIEWS={};

/* ---------- admin ---------- */
const stat=(l,n)=>`<div class="stat"><b>${n}</b><span>${l}</span></div>`;
VIEWS.overview=async()=>{
  const a=(await api('admin/analytics')).data;
  const tot=Object.values(a.status).reduce((x,y)=>x+y,0);
  const mx=Math.max(1,...a.daily.map(d=>d.count));
  const col={SCHEDULED:'#D9962B',COMPLETED:'#0E5A4C',CANCELLED:'#E8553D'};
  view(`<div class="stats">${stat('Patients',a.users.PATIENT)}${stat('Healthcare professionals',a.users.DOCTOR)}${stat('Appointments booked',tot)}${stat('Consultations given',a.consultations)}</div>
  <div class="grid2"><section class="panel"><h3>Appointments in the last 7 days</h3><div class="bars">${a.daily.map(d=>`<div class="bar"><b>${d.count}</b><i style="height:${Math.round(d.count/mx*80)+2}%"></i><span>${MONTHS[+d.date.slice(5,7)-1]} ${+d.date.slice(8)}</span></div>`).join('')}</div></section>
  <section class="panel"><h3>Status mix</h3><div class="stack">${Object.entries(a.status).map(([k,v])=>`<span style="width:${tot?v/tot*100:0}%;background:${col[k]}"></span>`).join('')}</div>
   <div class="legend">${Object.entries(a.status).map(([k,v])=>`<span><i style="background:${col[k]}"></i>${k.toLowerCase()} ${v}</span>`).join('')}</div>
   <h3 style="margin-top:24px">Busiest professionals</h3><ul class="rank">${a.topDoctors.length?a.topDoctors.map(d=>`<li><span>${esc(d.name)}</span><b>${d.count}</b></li>`).join(''):'<li class="muted">No bookings yet</li>'}</ul>
   <p class="muted" style="margin:14px 0 0">Health records stored: <b>${a.records}</b></p></section></div>`);
};
VIEWS.users=async()=>{
  S.users=(await api('admin/users')).data;
  view(`<section class="panel"><div class="bar-head"><input class="in" id="uq" placeholder="Search name, email or role" oninput="drawUsers()"><button class="btn" onclick="userForm()">Add user</button></div><div class="scroll" id="ut"></div></section>`);
  drawUsers();
};
function drawUsers(){
  const q=($('#uq')||{value:''}).value.toLowerCase();
  const rows=S.users.filter(u=>(u.name+u.email+u.role).toLowerCase().includes(q));
  $('#ut').innerHTML=`<table class="tbl"><thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Status</th><th></th></tr></thead><tbody>${rows.map(u=>`<tr><td><b>${esc(u.name)}</b>${u.specialization?'<br><small class="muted">'+esc(u.specialization)+'</small>':''}</td><td>${esc(u.email)}</td><td><span class="pill">${u.role.toLowerCase()}</span></td><td><span class="pill ${u.active?'COMPLETED':'off'}">${u.active?'active':'inactive'}</span></td><td><div class="acts"><button class="btn ghost sm" onclick="userForm(${u.id})">Edit</button><button class="btn danger sm" onclick="delUser(${u.id})">Delete</button></div></td></tr>`).join('')||'<tr><td colspan="5" class="muted">No users match.</td></tr>'}</tbody></table>`;
}
function userForm(id){
  const u=S.users.find(x=>x.id===id)||{role:'PATIENT',active:1};
  modal(id?'Edit user':'Add user',`<label>Full name<input name="name" required minlength="2" value="${esc(u.name||'')}"></label>
   <label>Email<input name="email" type="email" required value="${esc(u.email||'')}"></label>
   <div class="row2"><label>Role<select name="role" onchange="$('#spf').hidden=this.value!=='DOCTOR'">${['ADMIN','DOCTOR','PATIENT'].map(r=>`<option ${u.role===r?'selected':''}>${r}</option>`).join('')}</select></label>
   <label>Status<select name="active"><option value="1" ${u.active?'selected':''}>Active</option><option value="0" ${u.active?'':'selected'}>Inactive</option></select></label></div>
   <label id="spf" ${u.role==='DOCTOR'?'':'hidden'}>Specialization<input name="specialization" value="${esc(u.specialization||'')}"></label>
   <div class="row2"><label>Phone<input name="phone" value="${esc(u.phone||'')}"></label><label>${id?'New password (optional)':'Password'}<input name="password" type="password" minlength="6" ${id?'':'required'}></label></div>`,
   async f=>{if(id)f.id=id;const r=await api('admin/users/save',f);toast(r.message);VIEWS.users()});
}
async function delUser(id){
  if(!confirm('Delete this user and all of their appointments, records and messages?'))return;
  await safe(async()=>{const r=await api('admin/users/delete',{id});toast(r.message);VIEWS.users()});
}
VIEWS.appts=async()=>{
  const a=(await api('appointments')).data;
  view(`<section class="panel"><div class="bar-head"><span class="muted">${a.length} appointment${a.length==1?'':'s'} in the system</span><button class="btn" onclick="adminBook()">Schedule appointment</button></div><div class="scroll"><table class="tbl"><thead><tr><th>When</th><th>Patient</th><th>Professional</th><th>Status</th><th></th></tr></thead><tbody>${a.map(x=>`<tr><td>${fd(x.appt_date)}<br><small class="muted">${ft(x.appt_time)}</small></td><td>${esc(x.patient_name)}</td><td>${esc(x.doctor_name)}</td><td><select class="in" style="margin:0;width:auto" onchange="setStatus(${x.id},this.value)">${['SCHEDULED','COMPLETED','CANCELLED'].map(s=>`<option ${x.status===s?'selected':''}>${s}</option>`).join('')}</select></td><td><div class="acts"><button class="btn danger sm" onclick="delAppt(${x.id})">Delete</button></div></td></tr>`).join('')||'<tr><td colspan="5" class="muted">No appointments yet.</td></tr>'}</tbody></table></div></section>`);
};
async function setStatus(id,status){await safe(async()=>{const r=await api('admin/appointments/status',{id,status});toast(r.message)})}
async function delAppt(id){
  if(!confirm('Delete this appointment permanently?'))return;
  await safe(async()=>{const r=await api('admin/appointments/delete',{id});toast(r.message);VIEWS.appts()});
}
async function adminBook(){
  await safe(async()=>{
    const us=(await api('admin/users')).data,docs=us.filter(u=>u.role==='DOCTOR'&&u.active),pats=us.filter(u=>u.role==='PATIENT'&&u.active);
    const d=modal('Schedule appointment',`<label>Patient<select name="patientId" required>${pats.map(p=>`<option value="${p.id}">${esc(p.name)}</option>`).join('')}</select></label>
     <label>Healthcare professional<select name="doctorId" required onchange="loadSlots(this.form)"><option value="">Select...</option>${docs.map(p=>`<option value="${p.id}">${esc(p.name)}</option>`).join('')}</select></label>
     <div class="row2"><label>Date<input name="date" type="date" min="${today()}" value="${today()}" required onchange="loadSlots(this.form)"></label><label>Time<select name="time" required><option value="">Choose doctor and date</option></select></label></div>
     <label>Reason<input name="reason" maxlength="300"></label>`,
     async f=>{const r=await api('appointments/book',f);toast(r.message);VIEWS.appts()},'Schedule');
  });
}
VIEWS.settings=async()=>{
  const s=(await api('admin/settings')).data;
  view(`<section class="panel" style="max-width:620px"><p class="lede">These settings apply across the whole platform and take effect immediately.</p>
  <form onsubmit="saveSettings(event)"><label>Clinic name<input name="clinic_name" required value="${esc(s.clinic_name)}"></label>
  <div class="row2"><label>Booking window (days ahead)<input name="booking_window_days" type="number" min="1" value="${esc(s.booking_window_days)}"></label>
  <label>Max upcoming visits per patient<input name="max_active_per_patient" type="number" min="1" value="${esc(s.max_active_per_patient)}"></label></div>
  <div class="row2"><label>Default slot length (minutes)<input name="default_slot_minutes" type="number" min="1" value="${esc(s.default_slot_minutes)}"></label>
  <label>Patient self-registration<select name="allow_registration"><option value="true" ${s.allow_registration==='true'?'selected':''}>Open</option><option value="false" ${s.allow_registration==='true'?'':'selected'}>Closed</option></select></label></div>
  <p style="margin-top:20px"><button class="btn">Save settings</button></p></form></section>`);
};
async function saveSettings(ev){
  ev.preventDefault();
  await safe(async()=>{const r=await api('admin/settings',Object.fromEntries(new FormData(ev.target)));toast(r.message);S.pub.clinicName=r.data.clinic_name;S.pub.allowRegistration=r.data.allow_registration==='true'});
}

/* ---------- healthcare professional ---------- */
VIEWS.consults=async()=>{
  const a=(await api('appointments')).data;S.appts=a;
  const up=a.filter(x=>x.status==='SCHEDULED').sort((x,y)=>(x.appt_date+x.appt_time).localeCompare(y.appt_date+y.appt_time));
  const done=a.filter(x=>x.status==='COMPLETED');
  const act=(x,l)=>`<button class="btn sm" onclick="consultForm(${x.id})">${l}</button><button class="btn ghost sm" onclick="openPatient(${x.patient_id})">Patient record</button>`;
  view(`<section class="panel"><h3>Upcoming consultations</h3>${up.map(x=>apptRow(x,act(x,'Provide advice'))).join('')||'<p class="muted">No upcoming consultations.</p>'}</section>
  <section class="panel" style="margin-top:16px"><h3>Completed</h3>${done.map(x=>apptRow(x,act(x,'Edit advice'))).join('')||'<p class="muted">Nothing completed yet.</p>'}</section>`);
};
function consultForm(id){
  const a=S.appts.find(x=>x.id===id);
  modal('Consultation: '+a.patient_name,`<p class="muted">${fd(a.appt_date)} at ${ft(a.appt_time)}${a.reason?' - '+esc(a.reason):''}</p>
   <label>Diagnosis<input name="diagnosis" maxlength="200" value="${esc(a.diagnosis||'')}"></label>
   <label>Medical advice<textarea name="advice" rows="5" required>${esc(a.advice||'')}</textarea></label>
   <label>Prescription<textarea name="prescription" rows="2">${esc(a.prescription||'')}</textarea></label>`,
   async f=>{f.appointmentId=id;const r=await api('doctor/consult',f);toast(r.message);VIEWS.consults()},'Save consultation');
}
VIEWS.schedule=async()=>{
  const rows=(await api('doctor/schedule')).data,by={};rows.forEach(r=>by[r.weekday]=r);
  view(`<p class="lede">Set the hours patients can book. Time slots are generated from your working hours; days without hours are not bookable.</p><div class="sched">${[1,2,3,4,5,6,7].map(w=>{const r=by[w];return `<form class="day ${r?'':'off'}" onsubmit="saveDay(event,${w})"><b>${DAYS[w]}</b><input class="in" type="time" name="start" value="${r?r.start_time:'09:00'}" required aria-label="Start"><input class="in" type="time" name="end" value="${r?r.end_time:'17:00'}" required aria-label="End"><select class="in" name="slot" aria-label="Slot length">${[10,15,20,30,45,60].map(m=>`<option value="${m}" ${(r?r.slot_minutes:30)==m?'selected':''}>${m} min</option>`).join('')}</select><button class="btn sm">${r?'Update':'Set hours'}</button>${r?`<button type="button" class="btn ghost sm" onclick="dayOff(${w})">Day off</button>`:'<span class="muted">Day off</span>'}</form>`}).join('')}</div>`);
};
async function saveDay(ev,w){
  ev.preventDefault();
  await safe(async()=>{const f=Object.fromEntries(new FormData(ev.target));f.weekday=w;const r=await api('doctor/schedule/save',f);toast(r.message);VIEWS.schedule()});
}
async function dayOff(w){await safe(async()=>{const r=await api('doctor/schedule/off',{weekday:w});toast(r.message);VIEWS.schedule()})}
VIEWS.patients=async()=>{
  const p=(await api('doctor/patients')).data;
  view(`<section class="panel"><form class="bar-head" onsubmit="lookup(event)"><span class="muted">Look up a patient by ID, or pick one below.</span><span style="display:flex;gap:8px"><input class="in" name="pid" type="number" min="1" placeholder="Patient ID" required style="width:150px;margin:0"><button class="btn">Open record</button></span></form>
  <div class="scroll"><table class="tbl"><thead><tr><th>ID</th><th>Patient</th><th>Phone</th><th>Visits</th><th>Last visit</th><th></th></tr></thead><tbody>${p.map(x=>`<tr><td>${x.id}</td><td><b>${esc(x.name)}</b><br><small class="muted">${esc(x.email)}</small></td><td>${esc(x.phone||'-')}</td><td>${x.visits}</td><td>${fd(x.last_visit)}</td><td><div class="acts"><button class="btn ghost sm" onclick="openPatient(${x.id})">Open record</button></div></td></tr>`).join('')||'<tr><td colspan="6" class="muted">Patients appear here after they book with you.</td></tr>'}</tbody></table></div></section>`);
};
function lookup(ev){ev.preventDefault();openPatient(ev.target.pid.value)}
async function openPatient(id){
  await safe(async()=>{
    const d=(await api('doctor/patient?'+qs({patientId:id}))).data,p=d.profile;
    modal(p.name+' (ID '+p.id+')',`<p class="muted">${esc(p.email)} - ${esc(p.phone||'no phone on file')}</p>
     <div class="sec">Health records</div>${d.records.map(r=>`<div class="rec" style="margin-bottom:8px"><span class="pill">${esc(r.record_type)}</span> <small class="muted">${fd(r.record_date)}</small><h4>${esc(r.title)}</h4><p style="margin:0">${esc(r.details||'')}</p></div>`).join('')||'<p class="muted">No records added by the patient.</p>'}
     <div class="sec">Consultation history</div>${d.history.map(h=>`<div class="advice" style="margin-bottom:8px"><small class="muted">${fd(h.appt_date)} with ${esc(h.doctor_name)}</small><br>${h.diagnosis?'<b>Diagnosis:</b> '+esc(h.diagnosis)+'<br>':''}<b>Advice:</b> ${esc(h.advice)}${h.prescription?'<br><b>Prescription:</b> '+esc(h.prescription):''}</div>`).join('')||'<p class="muted">No consultations yet.</p>'}`,null,null,true);
  });
}

/* ---------- patient ---------- */
VIEWS.book=async()=>{
  const docs=(await api('doctors')).data;S.pick={doc:null,time:null,date:today()};
  view(`<div class="split"><section class="panel"><h3>Choose a professional</h3><div class="docs">${docs.map(d=>`<button class="doc" data-id="${d.id}" onclick="pickDoc(${d.id})"><span class="av">${esc(initials(d.name))}</span><b>${esc(d.name)}</b><small>${esc(d.specialization||'General')}</small></button>`).join('')||'<p class="muted">No professionals available yet.</p>'}</div></section>
  <section class="panel" id="bk"><h3>Pick a time</h3><p class="muted">Select a professional to see their availability.</p></section></div>`);
};
function pickDoc(id){
  S.pick.doc=id;S.pick.time=null;
  document.querySelectorAll('.doc').forEach(b=>b.classList.toggle('on',+b.dataset.id===id));
  $('#bk').innerHTML=`<h3>Pick a time</h3><label>Date<input type="date" id="bd" min="${today()}" value="${S.pick.date}" onchange="S.pick.date=this.value;S.pick.time=null;drawSlots()"></label><div id="sl" class="chips"></div>
   <label>Reason for visit<textarea id="br" rows="3" maxlength="300" placeholder="Briefly describe your symptoms"></textarea></label><p><button class="btn" onclick="confirmBook()">Confirm booking</button></p>`;
  drawSlots();
}
async function drawSlots(){
  await safe(async()=>{
    const r=await api('slots?'+qs({doctorId:S.pick.doc,date:S.pick.date}));
    $('#sl').innerHTML=r.data.length?r.data.map(t=>`<button class="chip" data-t="${t}" onclick="pickTime('${t}')">${ft(t)}</button>`).join(''):'<p class="muted">No free slots on this day. Try another date.</p>';
  });
}
function pickTime(t){S.pick.time=t;document.querySelectorAll('.chip').forEach(b=>b.classList.toggle('on',b.dataset.t===t))}
async function confirmBook(){
  if(!S.pick.time)return toast('Please choose a time slot',true);
  await safe(async()=>{
    const r=await api('appointments/book',{doctorId:S.pick.doc,date:S.pick.date,time:S.pick.time,reason:$('#br').value});
    toast(r.message);go('visits');
  });
}
VIEWS.visits=async()=>{
  const a=(await api('appointments')).data;
  const up=a.filter(x=>x.status==='SCHEDULED').sort((x,y)=>(x.appt_date+x.appt_time).localeCompare(y.appt_date+y.appt_time));
  const past=a.filter(x=>x.status!=='SCHEDULED');
  view(`<section class="panel"><div class="bar-head"><h3 style="margin:0">Upcoming</h3><button class="btn" onclick="go('book')">Book a consultation</button></div>${up.map(x=>apptRow(x,`<button class="btn danger sm" onclick="cancelAppt(${x.id})">Cancel</button>`)).join('')||'<p class="muted">No upcoming appointments.</p>'}</section>
  <section class="panel" style="margin-top:16px"><h3>Past and cancelled</h3>${past.map(x=>apptRow(x,'')).join('')||'<p class="muted">Nothing here yet.</p>'}</section>`);
};
async function cancelAppt(id){
  if(!confirm('Cancel this appointment?'))return;
  await safe(async()=>{const r=await api('appointments/cancel',{id});toast(r.message);VIEWS.visits()});
}
const REC_TYPES=['Condition','Allergy','Medication','Lab result','Vaccination','Surgery','Vitals','Other'];
VIEWS.records=async()=>{
  S.recs=(await api('patient/records')).data;
  view(`<div class="bar-head"><span class="muted">Your records are visible only to you and to professionals you have booked with.</span><button class="btn" onclick="recForm()">Add record</button></div>
  <div class="cards">${S.recs.map(r=>`<div class="rec"><span class="pill">${esc(r.record_type)}</span> <small class="muted">${fd(r.record_date)}</small><h4>${esc(r.title)}</h4><p>${esc(r.details||'No details')}</p><div class="acts" style="justify-content:flex-start"><button class="btn ghost sm" onclick="recForm(${r.id})">Edit</button><button class="btn danger sm" onclick="delRec(${r.id})">Delete</button></div></div>`).join('')||'<p class="muted">No records yet. Add allergies, medications or lab results so your doctor has the full picture.</p>'}</div>`);
};
function recForm(id){
  const r=S.recs.find(x=>x.id===id)||{record_type:'Condition',record_date:today()};
  modal(id?'Edit record':'Add health record',`<div class="row2"><label>Type<select name="type">${REC_TYPES.map(t=>`<option ${r.record_type===t?'selected':''}>${t}</option>`).join('')}</select></label><label>Date<input name="date" type="date" max="${today()}" value="${r.record_date}"></label></div>
   <label>Title<input name="title" required maxlength="120" value="${esc(r.title||'')}"></label><label>Details<textarea name="details" rows="4" maxlength="1000">${esc(r.details||'')}</textarea></label>`,
   async f=>{if(id)f.id=id;const x=await api('patient/records/save',f);toast(x.message);VIEWS.records()});
}
async function delRec(id){
  if(!confirm('Delete this health record?'))return;
  await safe(async()=>{const r=await api('patient/records/delete',{id});toast(r.message);VIEWS.records()});
}
VIEWS.advice=async()=>{
  const a=(await api('appointments')).data.filter(x=>x.consult_id);
  view(`<section class="panel">${a.map(x=>apptRow(x,'')).join('')||'<p class="muted">Advice from your doctors will appear here after a consultation.</p>'}</section>`);
};

/* ---------- messages (doctor and patient) ---------- */
VIEWS.messages=async()=>{
  const cs=(await api('messages/contacts')).data;
  view(`<div class="chat"><aside class="panel" style="position:static;height:auto;background:var(--surface);color:inherit;padding:10px">${cs.map(c=>`<button class="ct" data-id="${c.id}" onclick="openThread(${c.id})"><span class="av">${esc(initials(c.name))}</span><span><b>${esc(c.name)}</b><small>${esc(c.specialization||'Patient')}</small></span>${c.unread?`<i class="badge">${c.unread}</i>`:''}</button>`).join('')||'<p class="muted" style="padding:10px">Conversations open once you have an appointment together.</p>'}</aside>
  <section class="panel" id="th"><p class="muted">Select a conversation.</p></section></div>`);
};
async function openThread(id){
  S.chat=id;
  document.querySelectorAll('.ct').forEach(b=>{b.classList.toggle('on',+b.dataset.id===id);if(+b.dataset.id===id){const i=$('.badge',b);if(i)i.remove()}});
  $('#th').innerHTML=`<div class="msgs" id="msgs"></div><form class="send" onsubmit="sendMsg(event)"><input class="in" name="body" maxlength="2000" placeholder="Write a message" required autocomplete="off"><button class="btn">Send</button></form>`;
  await drawThread(true);
  clearInterval(S.timer);S.timer=setInterval(()=>drawThread(false),6000);
}
async function drawThread(force){
  if(S.view!=='messages'||!$('#msgs'))return;
  try{
    const r=(await api('messages/thread?'+qs({userId:S.chat}))).data,box=$('#msgs');
    const nearEnd=box.scrollHeight-box.scrollTop-box.clientHeight<80;
    box.innerHTML=r.map(m=>`<div class="m ${m.sender_id===S.me.id?'me':''}">${esc(m.body)}<small>${esc(m.created_at.slice(5,16))}</small></div>`).join('')||'<p class="muted">No messages yet. Say hello.</p>';
    if(force||nearEnd)box.scrollTop=box.scrollHeight;
  }catch(e){if(force)toast(e.message,true)}
}
async function sendMsg(ev){
  ev.preventDefault();const f=ev.target;
  await safe(async()=>{await api('messages/send',{to:S.chat,body:f.body.value});f.reset();await drawThread(true)});
}

/* ---------- profile ---------- */
VIEWS.profile=async()=>{
  const m=(await api('me')).data;
  view(`<section class="panel" style="max-width:560px"><form onsubmit="saveProfile(event)"><label>Email<input value="${esc(m.email)}" disabled></label><label>Full name<input name="name" required minlength="2" value="${esc(m.name)}"></label>
  <label>Phone<input name="phone" value="${esc(m.phone||'')}"></label><label>New password (leave blank to keep current)<input name="password" type="password" minlength="6" autocomplete="new-password"></label>
  <p style="margin-top:20px"><button class="btn">Save profile</button></p></form></section>`);
};
async function saveProfile(ev){
  ev.preventDefault();
  await safe(async()=>{const r=await api('profile',Object.fromEntries(new FormData(ev.target)));toast(r.message);S.me.name=ev.target.name.value;document.querySelector('.me b').textContent=S.me.name});
}

/* ---------- boot ---------- */
(async function(){
  try{S.pub=(await api('public')).data}catch(e){}
  try{S.me=(await api('me')).data;showApp()}catch(e){showLogin()}
})();
</script>
</body>
</html>
""";

        static final String INDEX_HTML = HEAD.concat(SCRIPT);
    }
}
