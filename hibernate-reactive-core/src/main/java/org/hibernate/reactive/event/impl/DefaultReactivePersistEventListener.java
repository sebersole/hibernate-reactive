/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive.event.impl;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.CompletionStage;

import org.hibernate.HibernateException;
import org.hibernate.ObjectDeletedException;
import org.hibernate.PersistentObjectException;
import org.hibernate.engine.spi.EntityEntry;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.engine.spi.Status;
import org.hibernate.event.internal.EntityState;
import org.hibernate.event.internal.EventUtil;
import org.hibernate.event.spi.EventSource;
import org.hibernate.event.spi.PersistContext;
import org.hibernate.event.spi.PersistEvent;
import org.hibernate.event.spi.PersistEventListener;
import org.hibernate.id.ForeignGenerator;
import org.hibernate.jpa.event.spi.CallbackRegistryConsumer;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.hibernate.reactive.engine.impl.CascadingAction;
import org.hibernate.reactive.engine.impl.CascadingActions;
import org.hibernate.reactive.event.ReactivePersistEventListener;
import org.hibernate.reactive.logging.impl.Log;
import org.hibernate.reactive.logging.impl.LoggerFactory;

import static org.hibernate.pretty.MessageHelper.infoString;
import static org.hibernate.reactive.util.impl.CompletionStages.failedFuture;
import static org.hibernate.reactive.util.impl.CompletionStages.voidFuture;

/**
 * A reactific {@link org.hibernate.event.internal.DefaultPersistEventListener}.
 */
public class DefaultReactivePersistEventListener
		extends AbstractReactiveSaveEventListener<PersistContext>
		implements PersistEventListener, ReactivePersistEventListener, CallbackRegistryConsumer {
	private static final Log LOG = LoggerFactory.make( Log.class, MethodHandles.lookup() );

	@Override
	protected CascadingAction<PersistContext> getCascadeReactiveAction() {
		return CascadingActions.PERSIST;
	}

	@Override
	public void onPersist(PersistEvent event) throws HibernateException {
		throw new UnsupportedOperationException( "Call #reactivePersist(PersistEvent) instead" );
	}

	/**
	 * Handle the given create event.
	 *
	 * @param event The create event to be handled.
	 */
	@Override
	public CompletionStage<Void> reactiveOnPersist(PersistEvent event) {
		return reactiveOnPersist( event, PersistContext.create() );
	}

	@Override
	public void onPersist(PersistEvent event, PersistContext createdAlready) throws HibernateException {
		throw new UnsupportedOperationException( "Call #reactivePersist(PersistEvent, PersistContext) instead" );
	}

	/**
	 * Handle the given create event.
	 *
	 * @param event The create event to be handled.
	 */
	@Override
	public CompletionStage<Void> reactiveOnPersist(PersistEvent event, PersistContext createCache) {
		final SessionImplementor source = event.getSession();
		final Object object = event.getObject();
		final Object entity;
		if ( object instanceof HibernateProxy ) {
			LazyInitializer li = ( (HibernateProxy) object ).getHibernateLazyInitializer();
			if ( li.isUninitialized() ) {
				if ( li.getSession() == source ) {
					return voidFuture(); //NOTE EARLY EXIT!
				}
				else {
					return failedFuture( new PersistentObjectException( "uninitialized proxy passed to persist()" ) );
				}
			}
			entity = li.getImplementation();
		}
		else {
			entity = object;
		}

		final String entityName;
		if ( event.getEntityName() != null ) {
			entityName = event.getEntityName();
		}
		else {
			entityName = source.bestGuessEntityName( entity );
			event.setEntityName( entityName );
		}

		final EntityEntry entityEntry = source.getPersistenceContextInternal().getEntry( entity );
		EntityState entityState = EntityState.getEntityState( entity, entityName, entityEntry, source, true );
		if ( entityState == EntityState.DETACHED ) {
			// JPA 2, in its version of a "foreign generated", allows the id attribute value
			// to be manually set by the user, even though this manual value is irrelevant.
			// The issue is that this causes problems with the Hibernate unsaved-value strategy
			// which comes into play here in determining detached/transient state.
			//
			// Detect if we have this situation and if so null out the id value and calculate the
			// entity state again.

			// NOTE: entityEntry must be null to get here, so we cannot use any of its values
			final EntityPersister persister = source.getFactory()
					.getRuntimeMetamodels()
					.getMappingMetamodel()
					.getEntityDescriptor( entityName );
			if (persister.getIdentifierGenerator() instanceof ForeignGenerator) {
				if ( LOG.isDebugEnabled() && persister.getIdentifier( entity, source ) != null ) {
					LOG.debug( "Resetting entity id attribute to null for foreign generator" );
				}
				persister.setIdentifier( entity, null, source );
				entityState = EntityState.getEntityState( entity, entityName, entityEntry, source, true );
			}
		}

		switch ( entityState ) {
			case DETACHED: {
				return failedFuture( new PersistentObjectException(
						"detached entity passed to persist: " +
								EventUtil.getLoggableName( event.getEntityName(), entity )
				) );
			}
			case PERSISTENT: {
				return entityIsPersistent( event, createCache );
			}
			case TRANSIENT: {
				return entityIsTransient( event, createCache );
			}
			case DELETED: {
				entityEntry.setStatus( Status.MANAGED );
				entityEntry.setDeletedState( null );
				event.getSession().getActionQueue().unScheduleDeletion( entityEntry, event.getObject() );
				return entityIsDeleted( event, createCache );
			}
			default: {
				return failedFuture( new ObjectDeletedException(
						"deleted entity passed to persist",
						null,
						EventUtil.getLoggableName( event.getEntityName(), entity )
				) );
			}
		}
	}

	protected CompletionStage<Void> entityIsPersistent(PersistEvent event, PersistContext createCache) {
		LOG.trace( "Ignoring persistent instance" );
		final EventSource source = event.getSession();

		//TODO: check that entry.getIdentifier().equals(requestedId)

		final Object entity = source.getPersistenceContextInternal().unproxy( event.getObject() );
		final EntityPersister persister = source.getEntityPersister( event.getEntityName(), entity );

		return createCache.add( entity )
				? justCascade( createCache, source, entity, persister )
				: voidFuture();
	}

	private CompletionStage<Void> justCascade(PersistContext createCache, EventSource source, Object entity, EntityPersister persister) {
		//TODO: merge into one method!
		return cascadeBeforeSave( source, persister, entity, createCache )
				.thenCompose( v -> cascadeAfterSave( source, persister, entity, createCache ) );
	}

	/**
	 * Handle the given create event.
	 *
	 * @param event The save event to be handled.
	 * @param createCache The copy cache of entity instance to merge/copy instance.
	 */
	protected CompletionStage<Void> entityIsTransient(PersistEvent event, PersistContext createCache) {
		LOG.trace( "Saving transient instance" );

		final EventSource source = event.getSession();
		final Object entity = source.getPersistenceContextInternal().unproxy( event.getObject() );

		return createCache.add( entity )
				? reactiveSaveWithGeneratedId( entity, event.getEntityName(), createCache, source, false )
				: voidFuture();
	}

	private CompletionStage<Void> entityIsDeleted(PersistEvent event, PersistContext createCache) {
		final EventSource source = event.getSession();

		final Object entity = source.getPersistenceContextInternal().unproxy( event.getObject() );
		final EntityPersister persister = source.getEntityPersister( event.getEntityName(), entity );

		if ( LOG.isTraceEnabled() ) {
			LOG.tracef(
					"un-scheduling entity deletion [%s]",
					infoString( persister, persister.getIdentifier( entity, source ), source.getFactory() )
			);
		}

		return createCache.add( entity )
				? justCascade( createCache, source, entity, persister )
				: voidFuture();
	}
}
