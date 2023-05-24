package bguspl.set.ex;
import bguspl.set.Env;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    protected final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    protected final List<Integer> deck;
    protected final List<Integer> actualDeck;

    /**
     * True iff game should be terminated due to an external event.
     */
    protected volatile boolean terminate;// every change done on this variable has to be updated in RAM at once

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime;

    protected volatile Queue<Integer> setCheck;

    protected Thread dealerThread;

    private final int sleepTime=10;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        actualDeck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        setCheck = new LinkedList<Integer>();
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        dealerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        for (int i = 0; i < env.config.players; i++) {
            players[i].getThread().start();
        }
        while (!shouldFinish()) {
            placeCardsOnTable();
            if(env.config.hints){
                table.hints();
            }
            updateTimerDisplay(true);
            timerLoop();
            removeAllCardsFromTable();
        }
        announceWinners();
        for (int i = env.config.players-1; i >= 0 ; i--){ 
            players[i].terminate();
            players[i].getThread().interrupt();
            try {
                players[i].getThread().join();}
                catch (InterruptedException e) {}
        }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did
     * not time out.
     */
    private void timerLoop() {
        while (!shouldFinish() && System.currentTimeMillis() < reshuffleTime) {                                                                // shouldfinish
            sleepUntilWokenOrTimeout();
            setCheck();
            updateTimerDisplay(false);
        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    protected boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable(int[] set) {
        table.tabWriteLock.lock();
        for (int i = 0; i < set.length; i++) {
            int cardToRemove = set[i];
            if(table.cardToSlot[cardToRemove]!=null){ //checks if card has alredy been removed
                table.removeCard(table.cardToSlot[cardToRemove]);
                updateTimerDisplay(false);
            }   
            deck.remove((Integer) cardToRemove);
        }
        table.tabWriteLock.unlock();
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    protected void placeCardsOnTable() {
        table.tabWriteLock.lock();

        List<Integer> nullSlots = new CopyOnWriteArrayList<Integer>();
        for (int i = 0; i < env.config.tableSize ; i++) {
            if (table.slotToCard[i]==null){
                nullSlots.add(i);
            }
        }
        Collections.shuffle(actualDeck);
        Collections.shuffle(nullSlots);

        while(!nullSlots.isEmpty() && (!actualDeck.isEmpty())){
            table.placeCard(actualDeck.remove(0), nullSlots.remove(0));
            updateTimerDisplay(false);
        }
        table.tabWriteLock.unlock();
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some
     * purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        try {
            Thread.sleep(sleepTime);
        } catch (InterruptedException e) {}
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset) {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
        } else {
            if (reshuffleTime - System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis && reshuffleTime - System.currentTimeMillis()>=0) 
                env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), true);
            else if(reshuffleTime - System.currentTimeMillis()<0)
                 env.ui.setCountdown(0, true);
            else
                env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);

        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    protected void removeAllCardsFromTable() {
        table.tabWriteLock.lock();
        for (int i = 0; i < env.config.tableSize; i++) { // returning cards back to deck
            if (table.slotToCard[i] != null) {
                actualDeck.add(table.slotToCard[i]);
            }
        }

        List<Integer> removeFromSlots = new CopyOnWriteArrayList<Integer>();
        for (int i = 0; i < env.config.tableSize ; i++) {
            removeFromSlots.add(i);
        }

        Collections.shuffle(removeFromSlots);
        while(!removeFromSlots.isEmpty()){ // removing cards from table
            table.removeCard(removeFromSlots.remove(0));
            updateTimerDisplay(false);
        }
        table.tabWriteLock.unlock();
    }
    
    protected void setCheck(){
        if (!setCheck.isEmpty()) {
            int playerId;
            synchronized(this){
                playerId = setCheck.poll();}
            synchronized (players[playerId]) {
                int[] set = table.playerSet(playerId);
                if(set.length==env.config.featureSize){
                    boolean res = env.util.testSet(set);
                    if (res) {
                        table.tabWriteLock.lock();
                        removeCardsFromTable(set);
                        placeCardsOnTable();
                        table.tabWriteLock.unlock();
                        if(env.config.hints){
                            table.hints();
                        }
                        updateTimerDisplay(true);
                    }
                    players[playerId].isLegallSet = res;
                    }
                players[playerId].notifyAll();
        }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int maxScore = 0;
        for (int i = 0; i < env.config.players; i++) {
            int score = players[i].score();
            if (maxScore < score)
                maxScore = score;
        }
        int counter = 0;
        for (int i = 0; i < env.config.players; i++) {
            int score = players[i].score();
            if (maxScore == score)
                counter++;
        }
        int[] winners = new int[counter];
        int index = 0;
        for (int i = 0; i < env.config.players; i++) {
            int score = players[i].score();
            if (maxScore == score) {
                winners[index] = players[i].id;
                index++;
            }
        }
        env.ui.announceWinner(winners);
    }
}
