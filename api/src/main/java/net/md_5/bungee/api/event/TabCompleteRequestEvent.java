package net.md_5.bungee.api.event;

import com.google.common.base.Preconditions;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestions;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Cancellable;
import net.md_5.bungee.protocol.ProtocolConstants;

/**
 * Event called when a player uses tab completion.
 */
@Data
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class TabCompleteRequestEvent extends TargetedEvent implements Cancellable
{

    /**
     * Cancelled state.
     */
    private boolean cancelled;
    /**
     * The message the player has already entered.
     */
    private final String cursor;
    /**
     * Range corresponding to the last word of {@link #getCursor()}.
     * If you want your suggestions to be compatible with 1.12 and older
     * clients, you need to {@link #setSuggestions(Suggestions)} with
     * a range equals to this one.
     * For 1.13 and newer clients, any other range that cover any part of
     * {@link #getCursor()} is fine.<br>
     * To check if the client supports custom ranges, use
     * {@link #supportsCustomRange()}.
     */
    private final StringRange legacyCompatibleRange;
    /**
     * The suggestions that will be sent to the client. If this list is empty,
     * the request will be forwarded to the server.
     */
    private Suggestions suggestions;

    public TabCompleteRequestEvent(Connection sender, Connection receiver, String cursor, StringRange legacyCompatibleRange, Suggestions suggestions)
    {
        super( sender, receiver );
        this.cursor = cursor;
        this.legacyCompatibleRange = legacyCompatibleRange;
        this.suggestions = suggestions;
    }

    /**
     * Sets the suggestions that will be sent to the client.
     * If this list is empty, the request will be forwarded to the server.
     * @param suggestions the new Suggestions. Cannot be null.
     * @throws IllegalArgumentException if the client is on 1.12 or lower and
     * {@code suggestions.getRange()} is not equals to {@link #legacyCompatibleRange}.
     */
    public void setSuggestions(Suggestions suggestions)
    {
        Preconditions.checkNotNull( suggestions );
        Preconditions.checkArgument( supportsCustomRange() || legacyCompatibleRange.equals( suggestions.getRange() ),
                "Clients on 1.12 or lower versions don't support the provided range for tab-completion: " + suggestions.getRange()
                + ". Please use TabCompleteRequestEvent.getLegacyCompatibleRange() for legacy clients." );
        this.suggestions = suggestions;
    }

    /**
     * Convenient method to tell if the client supports custom range for
     * suggestions.
     * If the client is on 1.13 or above, this methods returns true, and any
     * range can be used for {@link #setSuggestions(Suggestions)}. Otherwise,
     * it returns false and the defined range must be equals to
     * {@link #legacyCompatibleRange}.
     * @return true if the client is on 1.13 or newer version, false otherwise.
     */
    public boolean supportsCustomRange()
    {
        return ( (ProxiedPlayer) getSender() ).getPendingConnection().getVersion() >= ProtocolConstants.MINECRAFT_1_13;
    }
}
