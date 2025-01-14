/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive.engine.impl;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.CompletionStage;

import org.hibernate.AssertionFailure;
import org.hibernate.HibernateException;
import org.hibernate.action.internal.CollectionAction;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.event.service.spi.EventListenerGroup;
import org.hibernate.event.spi.EventSource;
import org.hibernate.event.spi.PostCollectionRecreateEvent;
import org.hibernate.event.spi.PostCollectionRecreateEventListener;
import org.hibernate.event.spi.PostCollectionUpdateEvent;
import org.hibernate.event.spi.PostCollectionUpdateEventListener;
import org.hibernate.event.spi.PreCollectionRecreateEvent;
import org.hibernate.event.spi.PreCollectionRecreateEventListener;
import org.hibernate.event.spi.PreCollectionUpdateEvent;
import org.hibernate.event.spi.PreCollectionUpdateEventListener;
import org.hibernate.persister.collection.CollectionPersister;
import org.hibernate.reactive.engine.ReactiveExecutable;
import org.hibernate.reactive.logging.impl.Log;
import org.hibernate.reactive.logging.impl.LoggerFactory;
import org.hibernate.reactive.persister.collection.impl.ReactiveCollectionPersister;
import org.hibernate.reactive.util.impl.CompletionStages;
import org.hibernate.stat.spi.StatisticsImplementor;

import static org.hibernate.pretty.MessageHelper.collectionInfoString;


/**
 * Like {@link org.hibernate.action.internal.CollectionUpdateAction} but reactive
 *
 * @see org.hibernate.action.internal.CollectionUpdateAction
 */
public class ReactiveCollectionUpdateAction extends CollectionAction implements ReactiveExecutable {
	private static final Log LOG = LoggerFactory.make( Log.class, MethodHandles.lookup() );

	private final boolean emptySnapshot;

	public ReactiveCollectionUpdateAction(
			final PersistentCollection collection,
			final CollectionPersister persister,
			final Object key,
			final boolean emptySnapshot,
			final EventSource session) {
		super( persister, collection, key, session );
		this.emptySnapshot = emptySnapshot;
	}

	@Override
	public CompletionStage<Void> reactiveExecute() {
		final Object key = getKey();
		final SharedSessionContractImplementor session = getSession();
		final ReactiveCollectionPersister reactivePersister = (ReactiveCollectionPersister) getPersister();
		final CollectionPersister corePersister = getPersister();
		final PersistentCollection collection = getCollection();
		final boolean affectedByFilters = corePersister.isAffectedByEnabledFilters( session );

		preUpdate();

		CompletionStage<Void> updateStage = CompletionStages.voidFuture();

		// And then make sure that each operations is executed in its own stage maintaining the same order as in ORM
		if ( !collection.wasInitialized() ) {
			// If there were queued operations, they would have been processed
			// and cleared by now.
			// The collection should still be dirty.
			if ( !collection.isDirty() ) {
				throw new AssertionFailure( "collection is not dirty" );
			}
			//do nothing - we only need to notify the cache...
		}
		else if ( !affectedByFilters && collection.empty() ) {
			if ( !emptySnapshot ) {
				updateStage = updateStage
						.thenCompose( v -> reactivePersister.reactiveRemove( key, session ) )
						.thenAccept( count -> { /* We don't care, maybe we can log it as debug */} );
			}
		}
		else if ( collection.needsRecreate( corePersister ) ) {
			if ( affectedByFilters ) {
				throw LOG.cannotRecreateCollectionWhileFilterIsEnabled( collectionInfoString( corePersister, collection, key, session ) );
			}
			if ( !emptySnapshot ) {
				updateStage = updateStage
						.thenCompose( v -> reactivePersister.reactiveRemove( key, session ) )
						.thenAccept( count -> { /* We don't care, maybe we can log it as debug */} );
			}

			return updateStage
					.thenCompose( v -> reactivePersister
							.reactiveRecreate( collection, key, session )
							.thenAccept( ignore -> {
								session.getPersistenceContextInternal().getCollectionEntry( collection ).afterAction( collection );
								evict();
								postUpdate();
								final StatisticsImplementor statistics = session.getFactory().getStatistics();
								if ( statistics.isStatisticsEnabled() ) {
									statistics.updateCollection( corePersister.getRole() );
								}
							})
					);
		}
		else {
			updateStage = updateStage
					.thenCompose( v -> reactivePersister.reactiveDeleteRows( collection, key, session ) )
					.thenCompose( v -> reactivePersister.reactiveUpdateRows( collection, key, session ) )
					.thenCompose( v -> reactivePersister.reactiveInsertRows( collection, key, session ) );
		}

		return updateStage.thenAccept(v -> {
			session.getPersistenceContextInternal().getCollectionEntry( collection ).afterAction( collection );
			evict();
			postUpdate();

			final StatisticsImplementor statistics = session.getFactory().getStatistics();
			if ( statistics.isStatisticsEnabled() ) {
				statistics.updateCollection( corePersister.getRole() );
			}
		} );
	}

	@Override
	public void execute() throws HibernateException {
		// Unsupported in reactive see reactiveExecute()
		throw new UnsupportedOperationException( "Use reactiveExecute() instead" );
	}

	private void preUpdate() {
		final EventListenerGroup<PreCollectionUpdateEventListener> listenerGroup = getFastSessionServices().eventListenerGroup_PRE_COLLECTION_UPDATE;
		if ( listenerGroup.isEmpty() ) {
			return;
		}
		final PreCollectionUpdateEvent event = new PreCollectionUpdateEvent(
				getPersister(),
				getCollection(),
				eventSource()
		);
		for ( PreCollectionUpdateEventListener listener : listenerGroup.listeners() ) {
			listener.onPreUpdateCollection( event );
		}
	}

	private void postUpdate() {
		final EventListenerGroup<PostCollectionUpdateEventListener> listenerGroup = getFastSessionServices().eventListenerGroup_POST_COLLECTION_UPDATE;
		if ( listenerGroup.isEmpty() ) {
			return;
		}
		final PostCollectionUpdateEvent event = new PostCollectionUpdateEvent(
				getPersister(),
				getCollection(),
				eventSource()
		);
		for ( PostCollectionUpdateEventListener listener : listenerGroup.listeners() ) {
			listener.onPostUpdateCollection( event );
		}
	}

	private void preRecreate() {
		final EventListenerGroup<PreCollectionRecreateEventListener> listenerGroup = getFastSessionServices().eventListenerGroup_PRE_COLLECTION_RECREATE;
		if ( listenerGroup.isEmpty() ) {
			return;
		}
		final PreCollectionRecreateEvent event = new PreCollectionRecreateEvent( getPersister(), getCollection(), eventSource() );
		for ( PreCollectionRecreateEventListener listener : listenerGroup.listeners() ) {
			listener.onPreRecreateCollection( event );
		}
	}

	private void postRecreate() {
		final EventListenerGroup<PostCollectionRecreateEventListener> listenerGroup = getFastSessionServices().eventListenerGroup_POST_COLLECTION_RECREATE;
		if ( listenerGroup.isEmpty() ) {
			return;
		}
		final PostCollectionRecreateEvent event = new PostCollectionRecreateEvent( getPersister(), getCollection(), eventSource() );
		for ( PostCollectionRecreateEventListener listener : listenerGroup.listeners() ) {
			listener.onPostRecreateCollection( event );
		}
	}
}
