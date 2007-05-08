package com.reucon.openfire.plugin.archive.impl;

import com.reucon.openfire.plugin.archive.ArchiveManager;
import com.reucon.openfire.plugin.archive.PersistenceManager;
import com.reucon.openfire.plugin.archive.IndexManager;
import com.reucon.openfire.plugin.archive.ArchiveFactory;
import com.reucon.openfire.plugin.archive.model.ArchivedMessage;
import com.reucon.openfire.plugin.archive.model.Conversation;
import com.reucon.openfire.plugin.archive.model.Participant;
import org.jivesoftware.openfire.session.Session;
import org.xmpp.packet.Message;

import java.util.*;

/**
 * Default implementation of ArchiveManager.
 */
public class ArchiveManagerImpl implements ArchiveManager
{
    private final PersistenceManager persistenceManager;
    private final IndexManager indexManager;
    private final Collection<Conversation> activeConversations;
    private int conversationTimeout;

    public ArchiveManagerImpl(PersistenceManager persistenceManager, IndexManager indexManager,
                              int conversationTimeout)
    {
        this.persistenceManager = persistenceManager;
        this.indexManager = indexManager;
        this.conversationTimeout = conversationTimeout;

        activeConversations = persistenceManager.getActiveConversations(conversationTimeout);
    }

    public void archiveMessage(Session session, Message message)
    {
        final ArchivedMessage archivedMessage;
        final Conversation conversation;

        // TODO support groupchat
        if (! (message.getType() == Message.Type.normal || message.getType() == Message.Type.chat))
        {
            return;
        }

        archivedMessage = ArchiveFactory.createArchivedMessage(session, message);
        if (archivedMessage.isEmpty())
        {
            return;
        }

        conversation = determineConversation(archivedMessage);
        archivedMessage.setConversation(conversation);

        persistenceManager.saveMessage(archivedMessage);
        if (indexManager != null)
        {
            indexManager.indexObject(archivedMessage);
        }
    }

    public void setConversationTimeout(int conversationTimeout)
    {
        this.conversationTimeout = conversationTimeout;
    }

    private Conversation determineConversation(ArchivedMessage archivedMessage)
    {
        Conversation conversation = null;
        Collection<Conversation> staleConversations;

        staleConversations = new ArrayList<Conversation>();
        synchronized (activeConversations)
        {
            for (Conversation c : activeConversations)
            {
                if (c.isStale(conversationTimeout))
                {
                    staleConversations.add(c);
                    continue;
                }

                if (c.hasParticipant(archivedMessage.getTo()) && c.hasParticipant(archivedMessage.getFrom()))
                {
                    conversation = c;
                    break;
                }
            }

            activeConversations.removeAll(staleConversations);
            
            if (conversation == null)
            {
                final Participant p1;
                final Participant p2;

                conversation = new Conversation(archivedMessage.getTime());
                persistenceManager.createConversation(conversation);

                p1 = new Participant(archivedMessage.getTime(), archivedMessage.getFrom());
                conversation.addParticipant(p1);
                persistenceManager.createParticipant(p1, conversation.getId());

                p2 = new Participant(archivedMessage.getTime(), archivedMessage.getTo());
                conversation.addParticipant(p2);
                persistenceManager.createParticipant(p2, conversation.getId());
                activeConversations.add(conversation);
            }
            else
            {
                conversation.setEnd(archivedMessage.getTime());
                persistenceManager.updateConversationEnd(conversation);
            }
        }

        return conversation;
    }
}
