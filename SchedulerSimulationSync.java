
import java.util.LinkedList;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.Semaphore;

// ANSI Color Codes for enhanced terminal output (same as Assignment 1)
class Colors {
    public static final String RESET = "\u001B[0m";
    public static final String BOLD = "\u001B[1m";
    public static final String CYAN = "\u001B[36m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String MAGENTA = "\u001B[35m";
    public static final String BLUE = "\u001B[34m";
    public static final String RED = "\u001B[31m";
    public static final String BG_BLUE = "\u001B[44m";
    public static final String BG_GREEN = "\u001B[42m";
    public static final String WHITE = "\u001B[37m";
    public static final String BRIGHT_WHITE = "\u001B[97m";
    public static final String BRIGHT_CYAN = "\u001B[96m";
    public static final String BRIGHT_YELLOW = "\u001B[93m";
    public static final String BRIGHT_GREEN = "\u001B[92m";
}

// DO NOT REMOVE OR MODIFY THIS CLASS
// A race condition happens when a thread is preempted BETWEEN reading a shared value and writing it back.
// On a real machine this window is only a few nanoseconds wide, so the bug appears rarely and randomly
// (which is exactly what makes race conditions so dangerous!). To make the bug show up reliably in this
// assignment, the starter code calls RaceWindow.pause() inside the unprotected code: it simulates the
// operating system preempting the thread at the worst possible moment.
class RaceWindow {
    static void pause() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

// Global simulation settings
class Config {
    // All sleeps are divided by TIME_SCALE so that the simulation runs 5x faster than in Assignment 1.
    // Times displayed in the output are still the "nominal" times (burst time, quantum, ...) in ms.
    static final int TIME_SCALE = 5;
}

// SHARED STATISTICS: updated by ALL the process threads at the same time
class SharedStats {
    static int totalProcesses = 0;
    static int totalCPUs = 0;
    static int contextSwitchCount = 0;
    static int completedProcessCount = 0;
    static long totalServiceTime = 0;

    // Number of times a CPU found the ready queue empty and polled it again.
    static final java.util.concurrent.atomic.AtomicInteger busyWaitPolls =
            new java.util.concurrent.atomic.AtomicInteger(0);

    // Feature 2: Lock protecting all shared statistics
    private static final ReentrantLock statsLock = new ReentrantLock();

    public static void incrementContextSwitch() {
        // Feature 2: Protect the context switch counter
        statsLock.lock();
        try {
            int tmp = contextSwitchCount;
            RaceWindow.pause();
            contextSwitchCount = tmp + 1;
        } finally {
            statsLock.unlock();
        }
    }

    public static void incrementCompletedProcess() {
        // Feature 2: Protect the completed process counter
        statsLock.lock();
        try {
            int tmp = completedProcessCount;
            RaceWindow.pause();
            completedProcessCount = tmp + 1;
        } finally {
            statsLock.unlock();
        }
    }

    public static void addServiceTime(int ms) {
        // Feature 2: Protect the total service time
        statsLock.lock();
        try {
            long tmp = totalServiceTime;
            RaceWindow.pause();
            totalServiceTime = tmp + ms;
        } finally {
            statsLock.unlock();
        }
    }

    // Readers must also be protected
    public static int getCompletedProcessCount() {
        // Feature 2: Protect the completed process counter read
        statsLock.lock();
        try {
            return completedProcessCount;
        } finally {
            statsLock.unlock();
        }
    }

    public static int getContextSwitchCount() {
        // Feature 2: Protect the context switch counter read
        statsLock.lock();
        try {
            return contextSwitchCount;
        } finally {
            statsLock.unlock();
        }
    }

    public static long getTotalServiceTime() {
        // Feature 2: Protect the total service time read
        statsLock.lock();
        try {
            return totalServiceTime;
        } finally {
            statsLock.unlock();
        }
    }
}

// THE READY QUEUE: shared by ALL the CPU threads
class ReadyQueue {
    private final LinkedList<Thread> list = new LinkedList<>();
    private final Map<Thread, Process> map = new HashMap<>();

    // Feature 1: Lock protecting the ready queue list and map
    private final ReentrantLock queueLock = new ReentrantLock();

    // Feature 3: Counting semaphore representing available processes
    private final Semaphore readyCount = new Semaphore(0);

    // Add a process to the ready queue
    public void add(Process process) {
        // Feature 1: Protect the queue insertion
        Thread thread = new Thread(process);

        queueLock.lock();
        try {
            list.add(thread);
            map.put(thread, process);
        } finally {
            queueLock.unlock();
        }

        // Feature 3: Signal that one more process is available
        readyCount.release();

        System.out.println(Colors.BLUE + "  ➕ " + Colors.BOLD + Colors.CYAN + process.getName() + Colors.RESET +
                Colors.BLUE + " (Priority: " + process.getPriority() + ") added to ready queue" + Colors.RESET +
                " │ Remaining: " + Colors.YELLOW + process.getRemainingTime() + "ms" + Colors.RESET +
                " of " + process.getBurstTime() + "ms");
    }

    // Remove and return the thread at the head of the queue
    public Thread poll() {
        // Feature 1: Protect the queue removal
        queueLock.lock();
        try {
            if (list.isEmpty()) {
                return null;
            }

            Thread head = list.getFirst();
            RaceWindow.pause();
            list.removeFirst();
            return head;
        } finally {
            queueLock.unlock();
        }
    }

    public Process processOf(Thread thread) {
        // Feature 1: Protect access to the process map
        queueLock.lock();
        try {
            return map.get(thread);
        } finally {
            queueLock.unlock();
        }
    }

    // Names of the processes currently waiting in the queue
    public String snapshot() {
        // Feature 1: Protect the queue snapshot
        queueLock.lock();
        try {
            StringBuilder sb = new StringBuilder();

            for (Thread t : list) {
                if (sb.length() > 0) sb.append(" → ");
                sb.append(map.get(t).getName());
            }

            return sb.length() == 0 ? "empty" : sb.toString();
        } finally {
            queueLock.unlock();
        }
    }

    // Feature 3: Wait until a process becomes available
    public void acquire() throws InterruptedException {
        readyCount.acquire();
    }

    // Feature 3: Wake up all CPUs when all processes are completed
    public void wakeUpAllCPUs() {
        readyCount.release(SharedStats.totalCPUs);
    }
}

// Class representing a process (same as Assignment 1)
class Process implements Runnable {
    private final String name;
    private final int burstTime;
    private final int timeQuantum;
    private int remainingTime;
    private final int priority;
    private final long creationTime;
    private long completionTime = 0;
    private volatile int cpuId = 0;

    public Process(String name, int burstTime, int timeQuantum, int priority) {
        this.name = name;
        this.burstTime = burstTime;
        this.timeQuantum = timeQuantum;
        this.remainingTime = burstTime;
        this.priority = priority;
        this.creationTime = System.currentTimeMillis();
    }

    public void setCpuId(int cpuId) {
        this.cpuId = cpuId;
    }

    @Override
    public void run() {
        int runTime = Math.min(timeQuantum, remainingTime);

        // Assignment 1 - Feature 2: a context switch happens every time a process starts running on a CPU
        SharedStats.incrementContextSwitch();

        System.out.println(Colors.BRIGHT_GREEN + "  ▶ [CPU-" + cpuId + "] " + Colors.BOLD + Colors.CYAN + name +
                Colors.RESET + Colors.GREEN + " (Priority: " + priority + ") executing quantum" + Colors.RESET +
                " [" + runTime + "ms]");

        try {
            Thread.sleep(runTime / Config.TIME_SCALE);
        } catch (InterruptedException e) {
            System.out.println(Colors.RED + "  ✗ " + name + " was interrupted." + Colors.RESET);
        }

        remainingTime -= runTime;
        SharedStats.addServiceTime(runTime);

        int overallProgress = (int) (((double) (burstTime - remainingTime) / burstTime) * 100);

        System.out.println(Colors.YELLOW + "  ⏸ [CPU-" + cpuId + "] " + Colors.CYAN + name + Colors.RESET +
                " completed quantum " + Colors.BRIGHT_YELLOW + runTime + "ms" + Colors.RESET +
                " │ Overall progress: " + createProgressBar(overallProgress, 20) +
                Colors.MAGENTA + "  remaining: " + remainingTime + "ms" + Colors.RESET);

        if (remainingTime > 0) {
            System.out.println(Colors.BLUE + "  ↻ " + Colors.CYAN + name + Colors.RESET +
                    " yields CPU for context switch");
        } else {
            completionTime = System.currentTimeMillis();
            SharedStats.incrementCompletedProcess();

            System.out.println(Colors.BRIGHT_GREEN + "  ✓ " + Colors.BOLD + Colors.CYAN + name + Colors.RESET +
                    Colors.BRIGHT_GREEN + " finished execution!" + Colors.RESET);
        }
    }

    private String createProgressBar(int progress, int width) {
        int filled = (progress * width) / 100;
        StringBuilder bar = new StringBuilder("[");

        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? Colors.GREEN + "█" + Colors.RESET : Colors.WHITE + "░" + Colors.RESET);
        }

        bar.append("] ").append(progress).append("%");
        return bar.toString();
    }

    public String getName() { return name; }
    public int getBurstTime() { return burstTime; }
    public int getRemainingTime() { return remainingTime; }
    public int getPriority() { return priority; }
    public boolean isFinished() { return remainingTime <= 0; }

    // Nominal turnaround time (ms) = (completion - creation) scaled back to the nominal time
    public long getTurnaroundTime() {
        return completionTime == 0 ? 0 : (completionTime - creationTime) * Config.TIME_SCALE;
    }

    // Nominal waiting time (ms) = turnaround time - burst time
    public long getWaitingTime() {
        return completionTime == 0 ? 0 : Math.max(0, getTurnaroundTime() - burstTime);
    }
}

// A CPU of the simulated machine
class CpuWorker extends Thread {
    private final int cpuId;
    private final ReadyQueue readyQueue;

    public CpuWorker(int cpuId, ReadyQueue readyQueue) {
        super("CPU-" + cpuId);
        this.cpuId = cpuId;
        this.readyQueue = readyQueue;
    }

    @Override
    public void run() {

        // Feature 3: Replace busy waiting with blocking semaphore acquisition
        while (true) {

            Thread thread;

            try {
                readyQueue.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // Feature 3: Stop when the shutdown signal is received
            if (SharedStats.getCompletedProcessCount() >= SharedStats.totalProcesses) {
                return;
            }

            thread = readyQueue.poll();

            if (thread == null) {
                continue;
            }

            Process process = readyQueue.processOf(thread);

            System.out.println(Colors.MAGENTA + "  ┌─ [CPU-" + cpuId + "] picks " + Colors.BRIGHT_CYAN +
                    process.getName() + Colors.MAGENTA + " │ Ready queue: [" + Colors.BRIGHT_WHITE +
                    readyQueue.snapshot() + Colors.MAGENTA + "]" + Colors.RESET);

            process.setCpuId(cpuId);
            thread.start();

            try {
                thread.join();
            } catch (InterruptedException e) {
                System.out.println("CPU-" + cpuId + " interrupted.");
                Thread.currentThread().interrupt();
                return;
            }

            if (!process.isFinished()) {
                readyQueue.add(process);
            }

            // Feature 3: Wake up all CPUs when the last process has completed
            if (SharedStats.getCompletedProcessCount() >= SharedStats.totalProcesses) {
                readyQueue.wakeUpAllCPUs();
            }
        }
    }
}

public class SchedulerSimulationSync {
    public static void main(String[] args) {
        // IMPORTANT: Put your student ID here to seed the random number generator
        int studentID = 446540228;

        Random random = new Random(studentID);

        // Same random values as Assignment 1
        int timeQuantum = 2000 + random.nextInt(4) * 1000;
        int numProcesses = 10 + random.nextInt(11);

        int[] burstTimes = new int[numProcesses];

        for (int i = 0; i < numProcesses; i++) {
            burstTimes[i] = timeQuantum / 2 + random.nextInt(2 * timeQuantum + 1);
        }

        // New in Assignment 3: number of CPUs
        int numCPUs = 2 + random.nextInt(3);

        // Priorities come from a separate generator
        Random priorityRandom = new Random(studentID + 1L);

        SharedStats.totalProcesses = numProcesses;
        SharedStats.totalCPUs = numCPUs;

        ReadyQueue readyQueue = new ReadyQueue();
        List<Process> allProcesses = new ArrayList<>();

        printHeader(studentID, numProcesses, timeQuantum, numCPUs);

        // Create the processes and put them in the ready queue
        for (int i = 0; i < numProcesses; i++) {
            Process process = new Process(
                    "P" + (i + 1),
                    burstTimes[i],
                    timeQuantum,
                    1 + priorityRandom.nextInt(10)
            );

            allProcesses.add(process);
            readyQueue.add(process);
        }

        System.out.println("\n" + Colors.BOLD + Colors.GREEN +
                "╔════════════════════════════════════════════════════════════════════════════════╗" +
                Colors.RESET);

        System.out.println(Colors.BOLD + Colors.GREEN + "║" + Colors.RESET +
                Colors.BG_GREEN + Colors.WHITE + Colors.BOLD +
                "                  ▶  " + numCPUs + " CPUs STARTING (CONCURRENT SCHEDULER)  ◀                    " +
                Colors.RESET + Colors.BOLD + Colors.GREEN + "║" + Colors.RESET);

        System.out.println(Colors.BOLD + Colors.GREEN +
                "╚════════════════════════════════════════════════════════════════════════════════╝" +
                Colors.RESET + "\n");

        // Start the CPUs
        List<CpuWorker> cpus = new ArrayList<>();

        for (int i = 1; i <= numCPUs; i++) {
            CpuWorker cpu = new CpuWorker(i, readyQueue);
            cpus.add(cpu);
            cpu.start();
        }

        // Watchdog: if the CPUs do not terminate in a reasonable time
        long expectedMs = 0;

        for (int b : burstTimes) {
            expectedMs += b / Config.TIME_SCALE;
        }

        long timeoutMs = 3 * expectedMs / numCPUs + 15000;
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean stuck = false;

        try {
            for (CpuWorker cpu : cpus) {
                long left = deadline - System.currentTimeMillis();

                if (left > 0) {
                    cpu.join(left);
                }

                if (cpu.isAlive()) {
                    stuck = true;
                }
            }
        } catch (InterruptedException e) {
            System.out.println("Main thread interrupted.");
            Thread.currentThread().interrupt();
        }

        int expectedQuanta = 0;
        long expectedService = 0;

        for (int b : burstTimes) {
            expectedQuanta += (b + timeQuantum - 1) / timeQuantum;
            expectedService += b;
        }

        if (stuck) {
            System.out.println("\n" + Colors.BOLD + Colors.RED +
                    "⛔ WATCHDOG: the simulation did not finish (" + timeoutMs / 1000 +
                    " s). A process was lost, or a CPU is blocked forever, or the 'completed' counter lost an update." +
                    Colors.RESET);
        }

        printResults(allProcesses, expectedQuanta, expectedService, numProcesses);

        if (stuck) {
            System.exit(1);
        }
    }

    private static void printHeader(int studentID, int numProcesses, int timeQuantum, int numCPUs) {
        String line = "═".repeat(87);

        System.out.println("\n" + Colors.BOLD + Colors.BRIGHT_CYAN + "╔" + line + "╗" + Colors.RESET);

        System.out.println(Colors.BOLD + Colors.BRIGHT_CYAN + "║" + Colors.RESET +
                Colors.BG_BLUE + Colors.BRIGHT_WHITE + Colors.BOLD +
                "           CONCURRENT CPU SCHEDULER SIMULATION (ASSIGNMENT 3 - SYNCHRONIZATION)          " +
                Colors.RESET + Colors.BOLD + Colors.BRIGHT_CYAN + "║" + Colors.RESET);

        System.out.println(Colors.BOLD + Colors.BRIGHT_CYAN + "╠" + line + "╣" + Colors.RESET);

        printHeaderRow("  Processes:       ", String.valueOf(numProcesses));
        printHeaderRow("  Time Quantum:    ", timeQuantum + "ms (nominal, simulated " + Config.TIME_SCALE + "x faster)");
        printHeaderRow("  CPUs:            ", String.valueOf(numCPUs));
        printHeaderRow("  Student ID:      ", String.valueOf(studentID));

        System.out.println(Colors.BOLD + Colors.BRIGHT_CYAN + "╚" + line + "╝" + Colors.RESET + "\n");
    }

    private static void printHeaderRow(String label, String value) {
        System.out.println(Colors.BOLD + Colors.BRIGHT_CYAN + "║" + Colors.RESET +
                Colors.YELLOW + label + Colors.RESET +
                Colors.BRIGHT_YELLOW + String.format("%-68s", value) +
                Colors.BOLD + Colors.BRIGHT_CYAN + "║" + Colors.RESET);
    }

    // Verification block + Assignment 1 summary table
    private static void printResults(List<Process> processes, int expectedQuanta,
                                     long expectedService, int numProcesses) {

        System.out.println("\n" + Colors.BOLD + Colors.BRIGHT_GREEN +
                "═══ VERIFICATION (expected values are computed from YOUR seed) ═══" + Colors.RESET);

        boolean ok = true;

        ok &= verifyLine("Context switches (quanta executed)",
                expectedQuanta, SharedStats.getContextSwitchCount());

        ok &= verifyLine("Completed processes",
                numProcesses, SharedStats.getCompletedProcessCount());

        ok &= verifyLine("Total service time (ms)",
                expectedService, SharedStats.getTotalServiceTime());

        ok &= verifyLine("Busy-waiting polls (must be 0)",
                0, SharedStats.busyWaitPolls.get());

        System.out.println(ok
                ? Colors.BOLD + Colors.BRIGHT_GREEN + "  ✔ All shared values are consistent." + Colors.RESET
                : Colors.BOLD + Colors.RED + "  ✘ At least one check failed (see the ✘ lines above): a race condition or busy waiting is still present." + Colors.RESET);

        System.out.println("\n" + Colors.BOLD + Colors.BRIGHT_CYAN +
                "═══ Process Summary Table (nominal ms) ═══" + Colors.RESET);

        System.out.println(Colors.BOLD +
                String.format("%-9s %-9s %-11s %-13s %-13s",
                        "Process", "Priority", "Burst", "Waiting", "Turnaround") +
                Colors.RESET);

        System.out.println("─".repeat(58));

        for (Process p : processes) {
            System.out.println(String.format("%-9s %-9d %-11d %-13d %-13d",
                    p.getName(),
                    p.getPriority(),
                    p.getBurstTime(),
                    p.getWaitingTime(),
                    p.getTurnaroundTime()));
        }

        System.out.println();
    }

    private static boolean verifyLine(String label, long expected, long observed) {
        boolean good = expected == observed;

        System.out.println(String.format("  %-38s expected %-8d observed %-8d ", label, expected, observed) +
                (good ? Colors.BRIGHT_GREEN + "✔" : Colors.RED + "✘ MISMATCH") +
                Colors.RESET);

        return good;
    }
}
