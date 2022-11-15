/*
 * Copyright 2002-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.transaction.interceptor;

import java.lang.reflect.Method;

import org.springframework.lang.Nullable;

/**
 * Strategy interface used by {@link TransactionInterceptor} for metadata retrieval.
 *
 * <p>Implementations know how to source transaction attributes, whether from configuration,
 * metadata attributes at source level (such as annotations), or anywhere else.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 15.04.2003
 * @see TransactionInterceptor#setTransactionAttributeSource
 * @see TransactionProxyFactoryBean#setTransactionAttributeSource
 * @see org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
 */
public interface TransactionAttributeSource {

	/**
	 * Determine whether the given class is a candidate for transaction attributes
	 * in the metadata format of this {@code TransactionAttributeSource}.
	 * <p>If this method returns {@code false}, the methods on the given class
	 * will not get traversed for {@link #getTransactionAttribute} introspection.
	 * Returning {@code false} is therefore an optimization for non-affected
	 * classes, whereas {@code true} simply means that the class needs to get
	 * fully introspected for each method on the given class individually.
	 * @param targetClass the class to introspect
	 * @return {@code false} if the class is known to have no transaction
	 * attributes at class or method level; {@code true} otherwise. The default
	 * implementation returns {@code true}, leading to regular introspection.
	 * @since 5.2
	 *
	 * 来自Spring 5.2的一个新方法
	 * <p>
	 * 确定给定的类是否是此事务切入点的候选者，即是否有资格继续进行类方法匹配来判断是否能被进行事务增强
	 * <p>
	 * 如果此方法返回false，则不会遍历给定类上的方法并进行getTransactionAttribute的调用。
	 * 因此，该方法是对不受事务影响的类的优化，避免了多次方法级别的校验
	 * 而返回true则是意味着该类需要对给定类上的每个方法分别进行全面自省，
	 * 来判断是否真正可以被事务代理，而不是真正的可以被此事务通知器行代理。
	 *
	 * 默认实现返回true，从而导致常规的方法自省。
	 */
	default boolean isCandidateClass(Class<?> targetClass) {
		return true;
	}

	/**
	 * Return the transaction attribute for the given method,
	 * or {@code null} if the method is non-transactional.
	 * 返回给定方法的事务属性，返回null表示该方法无事务。
	 * @param method the method to introspect 查找的方法
	 * @param targetClass the target class (may be {@code null},
	 * in which case the declaring class of the method must be used)
	 * 目标类，可能是null，此时将使用方法反射获取的class
	 * @return the matching transaction attribute, or {@code null} if none found
	 * 匹配的事务属性，如果没有发现则返回null
	 */
	@Nullable
	TransactionAttribute getTransactionAttribute(Method method, @Nullable Class<?> targetClass);

}
