package bguspl.set.ex;
import bguspl.set.Env;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate
     * key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    protected volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * a queue that holds the slots that were chosen by the player
     */
    protected BlockingQueue<Integer> incomingActions;

    private Dealer dealer;

    // protected Object setCheckLock;

    protected volatile Boolean isLegallSet;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided
     *               manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        this.score = 0;
        isLegallSet = null;
        playerThread = new Thread(this);
        incomingActions = new ArrayBlockingQueue<Integer>(env.config.featureSize);
    }

    /**
     * The main player thread of each player starts here (main loop for the player
     * thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human)
            createArtificialIntelligence();
        while (!terminate) {
            try {
                synchronized (incomingActions) {
                    while (incomingActions.isEmpty()) {
                        incomingActions.wait();
                    }
                }
                int target = incomingActions.take();
                if (!table.removeToken(id, target)) {
                    if (table.currNumOfTokens(id) < env.config.featureSize) {
                        table.placeToken(id, target);
                        if (table.currNumOfTokens(id) == env.config.featureSize) {            
                            synchronized (this) {
                                    synchronized(dealer){ //prevent player's threads adding simultaneously
                                        dealer.setCheck.add(id);}
                                    wait();}
                            if (isLegallSet!=null){
                                if(isLegallSet)
                                    point();
                                else    
                                    penalty();
                            isLegallSet = null;
                            incomingActions.clear();
                            }
                        }
                    }
                }
            }
            catch (InterruptedException e) {}
        }
        if (!human)
            try {
                aiThread.join();
            } catch (InterruptedException ignored) {
            }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of
     * this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it
     * is not full.
     */
    private void createArtificialIntelligence() {
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                    int slot = (int) (Math.random() * env.config.tableSize);
                    keyPressed(slot);
                    } 
                env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
                }
        , "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (table.tabReadLock.tryLock()) {
            if (isLegallSet == null) {
                synchronized (incomingActions) {
                    incomingActions.offer(slot);
                    incomingActions.notifyAll();
                }
            }
            table.tabReadLock.unlock();
        } 
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration inthe unit tests
        score++;
        env.ui.setScore(id, score);
        long pointFreezeTime = System.currentTimeMillis() + env.config.pointFreezeMillis;
        env.ui.setFreeze(id, pointFreezeTime - System.currentTimeMillis());
        while (System.currentTimeMillis() < pointFreezeTime) {
            env.ui.setFreeze(id, pointFreezeTime - System.currentTimeMillis());
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
            }
        }
        env.ui.setFreeze(id, pointFreezeTime - System.currentTimeMillis());
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        long penaltyTime = System.currentTimeMillis() + env.config.penaltyFreezeMillis;
        env.ui.setFreeze(id, penaltyTime - System.currentTimeMillis());
        while (System.currentTimeMillis() < penaltyTime)
            env.ui.setFreeze(id, penaltyTime - System.currentTimeMillis());
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
        }
        env.ui.setFreeze(id, penaltyTime - System.currentTimeMillis());
    }

    public int score() {
        return score;
    }

    public Thread getThread() {
        return playerThread;
    }
}
