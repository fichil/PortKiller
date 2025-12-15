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

                String proc = queryProcessName(pid.intValue());
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
        List<String> output = exec(Arrays.asList("cmd", "/c", "netstat -ano | findstr :" + port));
        Set<Integer> pids = new LinkedHashSet<Integer>();

        // netstat 行示例:
        // TCP    0.0.0.0:8080    0.0.0.0:0    LISTENING    12345
        Pattern pattern = Pattern.compile("\\s+(LISTENING|ESTABLISHED|TIME_WAIT|CLOSE_WAIT)\\s+(\\d+)\\s*$");

        for (int i = 0; i < output.size(); i++) {
            String line = output.get(i).trim();
            if (line.length() == 0) continue;

            if (onlyListening && line.indexOf("LISTENING") < 0) {
                continue;
            }
            Matcher m = pattern.matcher(line);
            if (m.find()) {
                String pidStr = m.group(2);
                try {
                    pids.add(Integer.valueOf(pidStr));
                } catch (Exception ignore) {}
            }
        }
        return pids;
    }

    /** tasklist 查 PID 的进程名（可选） */
    public static String queryProcessName(int pid) throws Exception {
        List<String> out = exec(Arrays.asList("cmd", "/c", "tasklist /FI \"PID eq " + pid + "\""));
        if (out.size() <= 1) return "";
        // 第二行开始是数据行，粗暴返回整行即可
        return out.get(out.size() - 1).trim();
    }

    /** taskkill 杀进程 */
    public static int killPid(int pid, boolean force) throws Exception {
        String cmd = force ? ("taskkill /PID " + pid + " /F") : ("taskkill /PID " + pid);
        return execExitCode(Arrays.asList("cmd", "/c", cmd));
    }

    /** 执行命令并返回输出 */
    private static List<String> exec(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        List<String> lines = new ArrayList<String>();
        String charset = detectCmdCharset();

        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), charset));
        String line;
        while ((line = br.readLine()) != null) {
            lines.add(line);
        }
        p.waitFor();
        return lines;
    }

    private static int execExitCode(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "GBK"));
        String line;
        while ((line = br.readLine()) != null) {
            System.out.println("    " + line);
        }
        return p.waitFor();
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
