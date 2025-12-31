package org.example.main;

import org.example.ConfigLoader;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.nio.charset.Charset;

public class PortKiller {

    private static volatile String CACHED_CHARSET;
    public static void main(String[] args) throws Exception {
        List<Integer> ports = ConfigLoader.getPorts();
        boolean onlyListening = ConfigLoader.isOnlyListening();
        boolean forceKill = ConfigLoader.isForceKill();
        boolean dryRun = ConfigLoader.isDryRun();


        for (int i = 0; i < ports.size(); i++) {
            int port = ports.get(i);
            Set<Integer> pids = findPidsByPort(port, onlyListening);

            if (pids.isEmpty()) {
                System.out.println("[OK] Port " + port + " not in use.");
                continue;
            }

            System.out.println("[HIT] Port " + port + " used by PIDs: " + pids);

            Iterator<Integer> it = pids.iterator();
            while (it.hasNext()) {
                Integer pid = it.next();

                String proc = "";
                try {
                    proc = queryProcessName(pid.intValue());
                } catch (Exception e) {
                    proc = "[proc query skipped: " + e.getMessage() + "]";
                }

                System.out.println("  PID=" + pid + " PROC=" + proc);
                if (dryRun) {
                    System.out.println("  [DRY-RUN] Would kill PID=" + pid);
                    continue;
                }
                int code = killPid(pid.intValue(), forceKill);
                System.out.println("  kill result exitCode=" + code);
            }
        }
    }


    /** 通过 netstat -ano 查端口对应 PID */
    public static Set<Integer> findPidsByPort(int port, boolean onlyListening) throws Exception {
        List<String> output = exec(Arrays.asList("netstat.exe", "-ano"), 8000);
        Set<Integer> pids = new LinkedHashSet<Integer>();

        String needle = ":" + port;

        // netstat 行示例:
        // TCP    0.0.0.0:8080    0.0.0.0:0    LISTENING    12345
        Pattern pattern = Pattern.compile("\\s+(LISTENING|ESTABLISHED|TIME_WAIT|CLOSE_WAIT)\\s+(\\d+)\\s*$");

        for (int i = 0; i < output.size(); i++) {
            String line = output.get(i);
            if (line == null) continue;
            line = line.trim();
            if (line.length() == 0) continue;

            // 只匹配本端口（本地地址栏包含 :port）
            // 注意：netstat 本地地址列可能是 0.0.0.0:8080 或 [::]:8080
            if (line.indexOf(needle) < 0) continue;

            if (onlyListening && line.indexOf("LISTENING") < 0) continue;

            Matcher m = pattern.matcher(line);
            if (m.find()) {
                String pidStr = m.group(2);
                try { pids.add(Integer.valueOf(pidStr)); } catch (Exception ignore) {}
            }
        }
        return pids;
    }

    /** tasklist 查 PID 的进程名 */
    public static String queryProcessName(int pid) throws Exception {
        // 只取 Name，一行输出
        String script = "$p=Get-Process -Id " + pid + " -ErrorAction SilentlyContinue; "
                + "if($p){$p.ProcessName}else{''}";

        List<String> out = exec(Arrays.asList(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-Command", script
        ), 8000);

        if (out == null || out.isEmpty()) return "";
        return out.get(0).trim();
    }


    /** taskkill 杀进程 */
    public static int killPid(int pid, boolean force) throws Exception {
        try {
            List<String> cmd = new ArrayList<String>();
            cmd.add("taskkill.exe");
            cmd.add("/PID");
            cmd.add(String.valueOf(pid));
            if (force) cmd.add("/F");

            ExecResult r = execWithTimeout0(cmd, 15000);
            for (int i = 0; i < r.lines.size(); i++) System.out.println("    " + r.lines.get(i));
            return r.exitCode;

        } catch (RuntimeException ex) {
            // fallback: PowerShell Stop-Process
            System.out.println("    [WARN] taskkill timeout, fallback to Stop-Process. " + ex.getMessage());

            String script =
                    "try { " +
                            "  Stop-Process -Id " + pid + " -Force -ErrorAction Stop; " +
                            "} catch { } " +
                            "$p = Get-Process -Id " + pid + " -ErrorAction SilentlyContinue; " +
                            "if($p){ exit 2 } else { exit 0 }";

            ExecResult r2 = execWithTimeout0(Arrays.asList(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script
            ), 15000);

            if (r2.exitCode != 0) {
                throw new RuntimeException("Stop-Process failed, pid still exists: " + pid);
            }
            return 0;

        }
    }



    /** 执行命令并返回输出 */
    private static List<String> exec(List<String> command, long timeoutMs) throws Exception {
        return execWithTimeout(command, timeoutMs);
    }

    private static List<String> exec(List<String> command) throws Exception {
        return execWithTimeout(command, 5000);
    }


    private static int execExitCode(List<String> command) throws Exception {
        ExecResult r = execWithTimeout0(command, 5000);
        for (int i = 0; i < r.lines.size(); i++) {
            System.out.println("    " + r.lines.get(i));
        }
        return r.exitCode;
    }

    private static class ExecResult {
        List<String> lines;
        int exitCode;
    }

    private static List<String> execWithTimeout(List<String> command, long timeoutMs) throws Exception {
        ExecResult r = execWithTimeout0(command, timeoutMs);
        return r.lines;
    }

    private static ExecResult execWithTimeout0(List<String> command, long timeoutMs) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        final List<String> lines = new ArrayList<String>();
        final String charset = detectCmdCharset();

        final BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), charset));

        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String line;
                    while ((line = br.readLine()) != null) {
                        lines.add(line);
                    }
                } catch (IOException ignore) {
                } finally {
                    try { br.close(); } catch (Exception ignore2) {}
                }
            }
        });
        reader.setDaemon(true);
        reader.start();

        boolean finished = waitFor(p, timeoutMs);
        if (!finished) {
            // 超时：杀掉进程，避免永久卡住
            try { p.destroy(); } catch (Exception ignore) {}
            try { p.destroyForcibly(); } catch (Exception ignore) {}
            throw new RuntimeException("Command timeout after " + timeoutMs + "ms: " + command);
        }

        // 等读线程收尾（给一点缓冲）
        try { reader.join(200); } catch (InterruptedException ignore) {}

        ExecResult r = new ExecResult();
        r.lines = lines;
        r.exitCode = p.exitValue();
        return r;
    }

    private static boolean waitFor(Process p, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (true) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException running) {
                // still running
            }
            if (System.currentTimeMillis() - start > timeoutMs) {
                return false;
            }
            Thread.sleep(50);
        }
    }


    private static String detectCmdCharset() {
        if (CACHED_CHARSET != null) return CACHED_CHARSET;

        // 兜底：用 JVM 默认编码（比“Windows=GBK”更靠谱）
        String fallback = Charset.defaultCharset().name();

        try {
            // 只对 Windows 做 chcp 探测；非 Windows 直接用 JVM 默认
            String os = System.getProperty("os.name");
            boolean isWindows = os != null && os.toLowerCase().contains("win");
            if (!isWindows) {
                CACHED_CHARSET = fallback;
                return CACHED_CHARSET;
            }

            List<String> out = execRaw(Arrays.asList("cmd", "/c", "chcp"));
            int cp = parseCodePage(out);
            String cs = mapCodePageToCharset(cp, fallback);

            CACHED_CHARSET = cs;
            return CACHED_CHARSET;

        } catch (Exception e) {
            CACHED_CHARSET = fallback;
            return CACHED_CHARSET;
        }
    }

    private static int parseCodePage(List<String> lines) {
        if (lines == null) return -1;
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            if (s == null) continue;
            String digits = s.replaceAll("[^0-9]", "");
            if (digits.length() > 0) {
                try { return Integer.parseInt(digits); } catch (Exception ignore) {}
            }
        }
        return -1;
    }

    private static String mapCodePageToCharset(int cp, String fallback) {
        if (cp == 65001) return "UTF-8";
        if (cp == 936) return "GBK";       // CP936
        if (cp == 54936) return "GB18030"; // CP54936
        if (cp == 950) return "Big5";      // CP950
        if (cp == 437) return "Cp437";
        if (cp == 1252) return "windows-1252";
        return fallback;
    }

    /** 原始执行：仅用于跑 chcp（避免递归），用 JVM 默认编码读取即可 */
    private static List<String> execRaw(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        List<String> lines = new ArrayList<String>();
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.defaultCharset()));
        String line;
        while ((line = br.readLine()) != null) {
            lines.add(line);
        }
        p.waitFor();
        return lines;
    }
}
