/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive.provider.service;

import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.internal.MappingModelCreationProcess;
import org.hibernate.metamodel.spi.RuntimeModelCreationContext;
import org.hibernate.query.sqm.mutation.internal.cte.CteInsertStrategy;
import org.hibernate.query.sqm.mutation.internal.cte.CteMutationStrategy;
import org.hibernate.query.sqm.mutation.spi.SqmMultiTableInsertStrategy;
import org.hibernate.query.sqm.mutation.spi.SqmMultiTableMutationStrategy;
import org.hibernate.query.sqm.mutation.spi.SqmMultiTableMutationStrategyProvider;
import org.hibernate.reactive.query.sqm.mutation.internal.cte.ReactiveCteInsertStrategy;
import org.hibernate.reactive.query.sqm.mutation.internal.cte.ReactiveCteMutationStrategy;

public class ReactiveSqmMultiTableMutationStrategyProvider implements SqmMultiTableMutationStrategyProvider {

	@Override
	public SqmMultiTableMutationStrategy createMutationStrategy(
			EntityMappingType rootEntityDescriptor,
			MappingModelCreationProcess creationProcess) {
		final RuntimeModelCreationContext creationContext = creationProcess.getCreationContext();
		SqmMultiTableMutationStrategy mutationStrategy = mutationStrategy( rootEntityDescriptor, creationContext );
		if ( mutationStrategy instanceof CteMutationStrategy ) {
			return new ReactiveCteMutationStrategy( rootEntityDescriptor, creationContext );
		}
		return mutationStrategy;
	}

	private static SqmMultiTableMutationStrategy mutationStrategy(
			EntityMappingType rootEntityDescriptor,
			RuntimeModelCreationContext creationContext) {
		final SessionFactoryOptions options = creationContext.getSessionFactoryOptions();
		return options.getCustomSqmMultiTableMutationStrategy() != null
				? options.getCustomSqmMultiTableMutationStrategy()
				: creationContext.getDialect().getFallbackSqmMutationStrategy( rootEntityDescriptor, creationContext );
	}

	@Override
	public SqmMultiTableInsertStrategy createInsertStrategy(
			EntityMappingType rootEntityDescriptor,
			MappingModelCreationProcess creationProcess) {
		final RuntimeModelCreationContext creationContext = creationProcess.getCreationContext();
		final SqmMultiTableInsertStrategy insertStrategy = insertStrategy( rootEntityDescriptor, creationContext );
		if ( insertStrategy instanceof CteInsertStrategy ) {
			return new ReactiveCteInsertStrategy( rootEntityDescriptor, creationContext );
		}
		return insertStrategy;
	}

	private static SqmMultiTableInsertStrategy insertStrategy(
			EntityMappingType rootEntityDescriptor,
			RuntimeModelCreationContext creationContext) {
		final SessionFactoryOptions options = creationContext.getSessionFactoryOptions();
		return options.getCustomSqmMultiTableInsertStrategy() != null
				? options.getCustomSqmMultiTableInsertStrategy()
				: creationContext.getDialect().getFallbackSqmInsertStrategy( rootEntityDescriptor, creationContext );
	}
}
