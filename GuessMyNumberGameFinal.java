import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.text.SimpleDateFormat;

public class GuessMyNumberGameFinal {
    public static final String DATA_FILE = "gamedata_final.ser";
    public static final int MIN_NUM = 1;
    public static final int MAX_NUM = 10;
    public static final long ROUND_INTERVAL_MS = 60_000L; // 60 sec
    public static final int PAYOUT_MULTIPLIER = 8;
    private static Scanner scanner = new Scanner(System.in);
    private static GameData data;
    private static RoundManager roundManager;

    // --- Models ---
    static class User implements Serializable {
        String username;
        String password;
        long balance;
        List<Bet> betsHistory = new ArrayList<>();
        public User(String u, String p, long balance) { this.username=u; this.password=p; this.balance=balance; }
    }

    static class Bet implements Serializable {
        int roundId;
        int number;
        long amount;
        String timestamp;
        public Bet(int roundId, int number, long amount, String ts) {
            this.roundId = roundId; this.number = number; this.amount = amount; this.timestamp = ts;
        }
    }

    static class RoundResult implements Serializable {
        int roundId;
        long startTime;
        long revealTime;
        int revealedNumber;
        Map<Integer, Long> totalPerNumber = new HashMap<>();
        public RoundResult(int id) { this.roundId=id; this.startTime=System.currentTimeMillis(); }
    }

    static class GameData implements Serializable {
        Map<String, User> users = new HashMap<>();
        List<RoundResult> rounds = new ArrayList<>();
        List<Integer> revealedNumbersChart = new ArrayList<>();
        String adminPassword = "admin123";
        int nextRoundId = 1;
    }

    // --- Persistence ---
    static void saveData() {
        try(ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(DATA_FILE))) {
            oos.writeObject(data);
        } catch(Exception e) { System.out.println("Error saving data: " + e.getMessage()); }
    }

    static void loadData() {
        File f = new File(DATA_FILE);
        if (!f.exists()) { data = new GameData(); saveData(); return; }
        try(ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
            data = (GameData) ois.readObject();
        } catch(Exception e) { 
            System.out.println("Failed to load data, starting fresh. ("+e.getMessage()+")");
            data = new GameData(); saveData();
        }
    }

    // --- Round Manager ---
    static class RoundManager {
        private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        private volatile boolean revealing = false;
        private RoundResult currentRound;

        public RoundManager() {
            startNewRound();
            scheduler.scheduleAtFixedRate(() -> {
                try { revealRoundAutomatic(); } 
                catch(Exception e) { System.out.println("Round reveal error: "+e.getMessage()); }
            }, ROUND_INTERVAL_MS, ROUND_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }

        private synchronized void startNewRound() {
            currentRound = new RoundResult(data.nextRoundId++);
            for (int i=MIN_NUM;i<=MAX_NUM;i++) currentRound.totalPerNumber.put(i,0L);
            data.rounds.add(currentRound);
            saveData();
            System.out.println("\n--- New Round started (ID: "+currentRound.roundId+") ---");
            System.out.println("Auto reveal in 60 seconds unless admin forces earlier reveal.");
        }

        public synchronized RoundResult getCurrentRound() { return currentRound; }

        public synchronized void addBet(String username, int number, long amount) {
            long prev = currentRound.totalPerNumber.getOrDefault(number,0L);
            currentRound.totalPerNumber.put(number, prev + amount);
            saveData();
        }

        private synchronized void revealRoundAutomatic() {
            if (revealing) return;
            revealing = true;
            try { revealCurrentRound("AUTO"); } 
            finally { startNewRound(); revealing = false; }
        }

        public synchronized void forceRevealByAdmin() {
            if (revealing) { System.out.println("Reveal already in progress."); return; }
            revealing = true;
            try { revealCurrentRound("ADMIN_FORCE"); } 
            finally { startNewRound(); revealing = false; }
        }

        // --- ✅ Updated Winner Logic ---
        private synchronized void revealCurrentRound(String reason) {
    RoundResult rr = currentRound;
    rr.revealTime = System.currentTimeMillis();

    // STEP 1: Collect all bets per number
    Map<Integer, Long> numberBets = new HashMap<>();
    for (int i = MIN_NUM; i <= MAX_NUM; i++) numberBets.put(i, 0L);
    for (User u : data.users.values()) {
        for (Bet b : u.betsHistory) {
            if (b.roundId == rr.roundId) {
                numberBets.put(b.number, numberBets.get(b.number) + b.amount);
            }
        }
    }

    // STEP 2: Find minimum non-zero bet
    long minBet = Long.MAX_VALUE;
    int minBetNumber = -1;
    for (int i = MIN_NUM; i <= MAX_NUM; i++) {
        long amt = numberBets.get(i);
        if (amt > 0 && amt < minBet) {
            minBet = amt;
            minBetNumber = i;
        }
    }

    // STEP 3: Find zero-bet numbers
    List<Integer> zeroBetNumbers = new ArrayList<>();
    for (int i = MIN_NUM; i <= MAX_NUM; i++) {
        if (numberBets.get(i) == 0) zeroBetNumbers.add(i);
    }

    int winner = -1;

    // STEP 4: Apply winner selection logic
    if (minBetNumber != -1) { 
        long possiblePayout = minBet * PAYOUT_MULTIPLIER;
        long totalBet = 0;
        for (long amt : numberBets.values()) totalBet += amt;

        if (totalBet > possiblePayout) {
            winner = minBetNumber;
        } else {
            if (!zeroBetNumbers.isEmpty()) {
                Random rnd = ThreadLocalRandom.current();
                winner = zeroBetNumbers.get(rnd.nextInt(zeroBetNumbers.size()));
            } else {
                winner = minBetNumber;
            }
        }
    } else {
        winner = MIN_NUM + new Random().nextInt(MAX_NUM - MIN_NUM + 1);
    }

    rr.revealedNumber = winner;

    // STEP 5: Update payouts & collect winners
    Map<String, Long> roundWinners = new HashMap<>();
    for (User u : data.users.values()) {
        long win = 0;
        for (Bet b : u.betsHistory)
            if (b.roundId == rr.roundId && b.number == winner)
                win += b.amount * PAYOUT_MULTIPLIER;
        if (win > 0) roundWinners.put(u.username, win);
        u.balance += win;
    }

    // STEP 6: Update chart
    data.revealedNumbersChart.add(winner);
    if (data.revealedNumbersChart.size() > 1000)
        data.revealedNumbersChart.remove(0);

    saveData();

    // --- Display Round Reveal ---
    System.out.println("\n=== ROUND REVEAL (" + reason + ") ===");
    System.out.println("Round ID: " + rr.roundId);
    System.out.println("Winning Number: " + rr.revealedNumber);

    if (roundWinners.isEmpty()) {
        System.out.println("No winners this round.");
    } else {
        System.out.println("\nWinners:");
        for (Map.Entry<String, Long> e : roundWinners.entrySet()) {
            System.out.println("User: " + e.getKey() + " | Amount Won: ₹" + e.getValue());
        }
    }
    System.out.println("=================================\n");
}
        // --- End of Updated Logic ---

        public void shutdown() { scheduler.shutdownNow(); }
    }

    // --- App Logic ---
    public static void main(String[] args) {
        loadData();
        roundManager = new RoundManager();

        System.out.println("Guess My Number Game Final Version");

        while (true) {
            System.out.println("\nMain Menu:");
            System.out.println("1) Admin login");
            System.out.println("2) User login");
            System.out.println("3) Register new user");
            System.out.println("4) View revealed number chart (public)");
            System.out.println("5) Exit");
            System.out.print("Choose: ");
            String c = scanner.nextLine().trim();
            switch(c) {
                case "1": adminMenu(); break;
                case "2": userLoginMenu(); break;
                case "3": registerUserFlow(false); break;
                case "4": viewRevealedChart(); break;
                case "5": roundManager.shutdown(); saveData(); System.exit(0);
                default: System.out.println("Invalid choice.");
            }
        }
    }

    // --- Admin / User / Flow methods ---
    static void adminMenu() {
        System.out.print("Enter admin password: ");
        String pw = scanner.nextLine().trim();
        if (!pw.equals(data.adminPassword)) { System.out.println("Wrong password."); return; }
        boolean out=false;
        while(!out) {
            System.out.println("\nAdmin Menu:");
            System.out.println("1) Add user");
            System.out.println("2) Delete user");
            System.out.println("3) Credit user");
            System.out.println("4) List users");
            System.out.println("5) Force reveal now");
            System.out.println("6) Change admin password");
            System.out.println("7) View round history");
            System.out.println("8) View revealed number chart");
            System.out.println("9) Logout");
            System.out.print("Choose: ");
            String ch = scanner.nextLine().trim();
            switch(ch) {
                case "1": registerUserFlow(true); break;
                case "2": deleteUserFlow(); break;
                case "3": creditUserFlow(); break;
                case "4": listUsers(); break;
                case "5": roundManager.forceRevealByAdmin(); break;
                case "6": changeAdminPassword(); break;
                case "7": viewRoundsHistory(); break;
                case "8": viewRevealedChart(); break;
                case "9": out=true; break;
                default: System.out.println("Invalid."); break;
            }
        }
    }

    static void userLoginMenu() {
        System.out.print("Username: "); String u = scanner.nextLine().trim();
        System.out.print("Password: "); String p = scanner.nextLine().trim();
        User usr = data.users.get(u);
        if (usr==null || !usr.password.equals(p)) { System.out.println("Invalid."); return; }
        boolean out=false;
        while(!out) {
            System.out.println("\nUser Menu:");
            System.out.println("1) Place bet");
            System.out.println("2) View balance");
            System.out.println("3) View history");
            System.out.println("4) View revealed number chart (public)");
            System.out.println("5) Logout");
            System.out.print("Choose: ");
            String ch = scanner.nextLine().trim();
            switch(ch) {
                case "1": placeBetFlow(usr); break;
                case "2": System.out.println("Balance: ₹"+usr.balance); break;
                case "3": viewUserHistory(usr); break;
                case "4": viewRevealedChart(); break;
                case "5": out=true; break;
                default: System.out.println("Invalid."); break;
            }
        }
    }

    static void registerUserFlow(boolean byAdmin) {
        System.out.print("Username: ");
        String user = scanner.nextLine().trim();
        if (data.users.containsKey(user)) { 
            System.out.println("User already exists."); 
            return; 
        }
        System.out.print("Password: ");
        String pass = scanner.nextLine().trim();
        long bal = 0;
        if (byAdmin) {
            System.out.print("Initial credit ₹: "); 
            try { bal = Long.parseLong(scanner.nextLine().trim()); } catch(Exception e){ bal=0; }
        }
        data.users.put(user, new User(user, pass, bal));
        saveData();
        System.out.println("User registered successfully.");
    }

    static void deleteUserFlow() {
        System.out.print("Enter username: ");
        String u = scanner.nextLine().trim();
        if (!data.users.containsKey(u)) { System.out.println("Not found."); return; }
        data.users.remove(u); saveData();
        System.out.println("Deleted user: "+u);
    }

    static void creditUserFlow() {
        System.out.print("User: "); String u = scanner.nextLine().trim();
        User usr = data.users.get(u);
        if (usr==null) { System.out.println("No such user."); return; }
        System.out.print("Amount ₹: ");
        try {
            long a = Long.parseLong(scanner.nextLine().trim());
            usr.balance += a; saveData();
            System.out.println("Credited ₹"+a+". Balance: ₹"+usr.balance);
        } catch(Exception e){ System.out.println("Invalid."); }
    }

    static void listUsers() {
        System.out.println("Users:");
        for (User u : data.users.values())
            System.out.println(" - "+u.username+" | ₹"+u.balance);
    }

    static void changeAdminPassword() {
        System.out.print("New password: ");
        String np = scanner.nextLine().trim();
        data.adminPassword = np; saveData();
        System.out.println("Password updated.");
    }

    static void viewRoundsHistory() {
        for (RoundResult r : data.rounds) {
            String s = formatTime(r.startTime);
            String e = r.revealTime==0 ? "..." : formatTime(r.revealTime);
            System.out.println("Round "+r.roundId+" | Start: "+s+" | Reveal: "+e+" | Number: "+r.revealedNumber);
        }
    }

    static void placeBetFlow(User u) {
        RoundResult rr = roundManager.getCurrentRound();
        System.out.println("Round ID: "+rr.roundId);
        System.out.print("Number (1-10): ");
        int n; try { n = Integer.parseInt(scanner.nextLine().trim()); } catch(Exception e){ System.out.println("Invalid."); return; }
        if (n<1||n>10){System.out.println("1-10 only.");return;}
        System.out.print("Amount ₹: ");
        long a; try { a = Long.parseLong(scanner.nextLine().trim()); } catch(Exception e){ System.out.println("Invalid."); return; }
        if (a<=0){System.out.println("Must be >0");return;}
        if (u.balance < a){System.out.println("Insufficient balance.");return;}
        u.balance -= a;
        Bet b = new Bet(rr.roundId,n,a,formatTime(System.currentTimeMillis()));
        u.betsHistory.add(b);
        roundManager.addBet(u.username,n,a);
        saveData();
        System.out.println("Bet placed ₹"+a+" on "+n+" (Round "+rr.roundId+")");
    }

    static void viewUserHistory(User u) {
        System.out.println("Your bets:");
        for (Bet b : u.betsHistory)
            System.out.println("Round "+b.roundId+" | Num "+b.number+" | ₹"+b.amount+" | "+b.timestamp);
    }

    static void viewRevealedChart() {
        if (data.revealedNumbersChart.isEmpty()) { 
            System.out.println("No numbers revealed yet."); 
            return; 
        }
        System.out.println("\n--- Revealed Number Chart (Round → Number) ---");
        int roundIndex = 1;
        for (int num : data.revealedNumbersChart) {
            System.out.println("Round "+roundIndex+" → "+num);
            roundIndex++;
        }
    }

    static String formatTime(long ms) {
        return new SimpleDateFormat("HH:mm:ss").format(new Date(ms));
    }
}
