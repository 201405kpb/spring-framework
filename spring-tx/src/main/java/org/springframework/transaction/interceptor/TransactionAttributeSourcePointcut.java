/*
 * Copyright 2002-2021 the original author or authors.
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

import java.io.Serializable;
import java.lang.reflect.Method;

import org.springframework.aop.ClassFilter;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.lang.Nullable;
import org.springframework.transaction.TransactionManager;
import org.springframework.util.ObjectUtils;

/**
 * Abstract class that implements a Pointcut that matches if the underlying
 * {@link TransactionAttributeSource} has an attribute for a given method.
 *
 * @author Juergen Hoeller
 * @since 2.5.5
 */
@SuppressWarnings("serial")
abstract class TransactionAttributeSourcePointcut extends StaticMethodMatcherPointcut implements Serializable {

	/**
	 * 设置ClassFilter为一个TransactionAttributeSourceClassFilter实例
	 */
	protected TransactionAttributeSourcePointcut() {
		setClassFilter(new TransactionAttributeSourceClassFilter());
	}


	@Override
	public boolean matches(Method method, Class<?> targetClass) {
		TransactionAttributeSource tas = getTransactionAttributeSource();
		return (tas == null || tas.getTransactionAttribute(method, targetClass) != null);
	}

	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof TransactionAttributeSourcePointcut otherPc)) {
			return false;
		}
		return ObjectUtils.nullSafeEquals(getTransactionAttributeSource(), otherPc.getTransactionAttributeSource());
	}

	@Override
	public int hashCode() {
		return TransactionAttributeSourcePointcut.class.hashCode();
	}

	@Override
	public String toString() {
		return getClass().getName() + ": " + getTransactionAttributeSource();
	}


	/**
	 * Obtain the underlying TransactionAttributeSource (may be {@code null}).
	 * To be implemented by subclasses.
	 * 获取底层的TransactionAttributeSource(可以为null)。由子类实现。
	 * <p> 在BeanFactoryTransactionAttributeSourceAdvisor中的TransactionAttributeSourcePointcut就是一个匿名内部类实现
	 * 它的getTransactionAttributeSource会返回配置的AnnotationTransactionAttributeSource事务属性源
	 */
	@Nullable
	protected abstract TransactionAttributeSource getTransactionAttributeSource();


	/**
	 * {@link ClassFilter} that delegates to {@link TransactionAttributeSource#isCandidateClass}
	 * for filtering classes whose methods are not worth searching to begin with.
	 *
	 *  实际工作委托给TransactionAttributeSource的类过滤器。
	 *  TransactionAttributeSource的isCandidateClass方法用于过滤那些方法不值继续得搜索的类。
	 */
	private class TransactionAttributeSourceClassFilter implements ClassFilter {

		/**
		 * 匹配类的方法
		 * @param clazz bean的class
		 * @return true 成功 false 失败
		 */
		@Override
		public boolean matches(Class<?> clazz) {
			//如果当前bean的类型属于TransactionalProxy或者TransactionalProxy或者PersistenceExceptionTranslator
			//那么返回false，这些类的实例的方法不应该进行Spring 事务的代理
			if (TransactionalProxy.class.isAssignableFrom(clazz) ||
					TransactionManager.class.isAssignableFrom(clazz) ||
					PersistenceExceptionTranslator.class.isAssignableFrom(clazz)) {
				return false;
			}
			//如果不是上面那些类型的bean实例，就通过设置的TransactionAttributeSource来判断
			//如果TransactionAttributeSource不为null并且isCandidateClass方法返回true，
			//那么表示当前bean的class允许继续匹配方法，否则表示不会继续匹配，即当前bean实例不会进行事务代理
			TransactionAttributeSource tas = getTransactionAttributeSource();
			return (tas == null || tas.isCandidateClass(clazz));
		}
	}

}
