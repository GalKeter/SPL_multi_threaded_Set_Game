package bguspl.set.ex;

import bguspl.set.Env;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.concurrent.locks.ReentrantReadWriteLock;


/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected Integer[] cardToSlot; // slot per card (if any)

    /**
     * an array of the tokens on every slot.
     */
    protected boolean[][] tokens;

    public ReentrantReadWriteLock tabLock = new ReentrantReadWriteLock(true);
    public ReentrantReadWriteLock.ReadLock tabReadLock = tabLock.readLock();
    public ReentrantReadWriteLock.WriteLock tabWriteLock = tabLock.writeLock();

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {
        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        tokens = new boolean [env.config.players][env.config.tableSize];
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) { 
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        cardToSlot[card] = slot;
        slotToCard[slot] = card;
        env.ui.placeCard(card, slot);
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot) {  
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}
        if(slotToCard[slot]!=null){
            cardToSlot[slotToCard[slot]] = null; //updating the slot to be null
            slotToCard[slot] = null;
            for(int i=0; i<env.config.players ; i++){ //remove tokens
                if (tokens[i][slot]){        
                    tokens[i][slot] = false;
                    env.ui.removeToken(i, slot);
                }
            } 
            env.ui.removeCard(slot);
    } 
    }

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public void placeToken(int player, int slot) {
        if(tabReadLock.tryLock()){
            if (!tokens[player][slot] && slotToCard[slot]!=null){
                tokens[player][slot]=true;
                env.ui.placeToken(player, slot);
            }
            tabReadLock.unlock();
        }
    }
    
    public boolean removeToken(int player, int slot) {
        boolean ans = false;
        if(tabReadLock.tryLock()){
            if (tokens[player][slot]){
                tokens[player][slot] = false;
                env.ui.removeToken(player, slot);
                ans=true;
            }
            tabReadLock.unlock();
        }
        return ans;
    }

    public int currNumOfTokens(int playerId){
            int result=0;
            for(int i=0; i<env.config.tableSize; i++){
                if(tokens[playerId][i])
                    result++;
            }
            return result;
        }


    public int[] playerSet(int playerId){
        int[] set = new int[currNumOfTokens(playerId)];
        int index=0;
        for(int i=0; i<env.config.tableSize; i++){
            if(tokens[playerId][i]){
                set[index]=slotToCard[i];
                index++;
            }
        }
        return set;
    }
}
