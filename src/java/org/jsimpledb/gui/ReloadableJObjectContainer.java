
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package org.jsimpledb.gui;

import com.vaadin.server.VaadinSession;

import org.jsimpledb.JSimpleDB;
import org.jsimpledb.JTransaction;
import org.jsimpledb.core.Transaction;

/**
 * A {@link JObjectContainer} that supports a {@link #reload} operation.
 *
 * <p>
 * Instances automatically {@link #reload} themselves when {@link #connect}'ed.
 */
@SuppressWarnings("serial")
public abstract class ReloadableJObjectContainer extends JObjectContainer {

    private VaadinSession vaadinSession;

// Constructors

    /**
     * Constructor.
     *
     * @param jdb {@link JSimpleDB} database
     * @param type type restriction, or null for no restriction
     * @throws IllegalArgumentException if {@code jdb} is null
     */
    protected ReloadableJObjectContainer(JSimpleDB jdb, Class<?> type) {
        super(jdb, type);
    }

// Connectable

    @Override
    public void connect() {
        super.connect();
        this.vaadinSession = VaadinSession.getCurrent();
        this.reload();
    }

    @Override
    public void disconnect() {
        super.disconnect();
        this.vaadinSession = null;
    }

// Methods

    /**
     * (Re)load this container.
     *
     * <p>
     * May have no effect if this instance is not {@link #connect}'ed.
     */
    public abstract void reload();

    /**
     * Reload this container after the transaction open in the current thread successfully commits.
     *
     * <p>
     * Does nothing if the current transaction fails or this instance is not {@link #connect}'ed.
     *
     * @throws IllegalStateException if there is no {@link JTransaction} associated with the current thread
     */
    public void reloadAfterCommit() {
        JTransaction.getCurrent().getTransaction().addCallback(new Transaction.CallbackAdapter() {
            @Override
            public void afterCommit() {
                if (ReloadableJObjectContainer.this.vaadinSession == null)
                    return;
                ReloadableJObjectContainer.this.vaadinSession.access(new Runnable() {
                    @Override
                    public void run() {
                        if (ReloadableJObjectContainer.this.vaadinSession != null)
                            ReloadableJObjectContainer.this.reload();
                    }
                });
            }
        });
    }
}
