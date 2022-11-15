/*
 * Copyright 2002-2017 the original author or authors.
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

import org.springframework.aop.ClassFilter;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractBeanFactoryPointcutAdvisor;
import org.springframework.lang.Nullable;

/**
 * Advisor driven by a {@link TransactionAttributeSource}, used to include
 * a transaction advice bean for methods that are transactional.
 *
 * @author Juergen Hoeller
 * @since 2.5.5
 * @see #setAdviceBeanName
 * @see TransactionInterceptor
 * @see TransactionAttributeSourceAdvisor
 */
@SuppressWarnings("serial")
public class BeanFactoryTransactionAttributeSourceAdvisor extends
		AbstractBeanFactoryPointcutAdvisor {

	/**
	 * 事务属性源
	 */
	@Nullable
	private TransactionAttributeSource transactionAttributeSource;

	/**
	 * 事务切入点，用于判断某个bean/方法是否符合Spring事务切入规则
	 */
	private final TransactionAttributeSourcePointcut pointcut = new TransactionAttributeSourcePointcut() {
		@Override
		@Nullable
		protected TransactionAttributeSource getTransactionAttributeSource() {
			return transactionAttributeSource;
		}
	};


	/**
	 * 设置用于查找TransactionAttribute的TransactionAttributeSource。
	 *
	 * @see TransactionInterceptor#setTransactionAttributeSource
	 */
	public void setTransactionAttributeSource(TransactionAttributeSource transactionAttributeSource) {
		this.transactionAttributeSource = transactionAttributeSource;
	}

	/**
	 * 为这个切入点设置要使用的ClassFilter类筛选器。默认是ClassFilter.TRUE，即匹配所有类
	 */
	public void setClassFilter(ClassFilter classFilter) {
		this.pointcut.setClassFilter(classFilter);
	}

	/**
	 * 获取切入点
	 */
	@Override
	public Pointcut getPointcut() {
		return this.pointcut;
	}

}
