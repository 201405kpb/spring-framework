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

package org.springframework.transaction.annotation;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.annotation.Role;
import org.springframework.transaction.config.TransactionManagementConfigUtils;
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * {@code @Configuration} class that registers the Spring infrastructure beans
 * necessary to enable proxy-based annotation-driven transaction management.
 *
 * @author Chris Beams
 * @author Sebastien Deleuze
 * @see EnableTransactionManagement
 * @see TransactionManagementConfigurationSelector
 * @since 3.1
 */
@Configuration(proxyBeanMethods = false)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
@ImportRuntimeHints(TransactionRuntimeHints.class)
public class ProxyTransactionManagementConfiguration extends AbstractTransactionManagementConfiguration {

	/**
	 * 注册一个事务通知器bean定义，名为"org.springframework.transaction.config.internalTransactionAdvisor"
	 * 类型就是BeanFactoryTransactionAttributeSourceAdvisor
	 *
	 * @param transactionAttributeSource 事务属性源
	 * @param transactionInterceptor     事务拦截器
	 */
	@Bean(name = TransactionManagementConfigUtils.TRANSACTION_ADVISOR_BEAN_NAME)
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	public BeanFactoryTransactionAttributeSourceAdvisor transactionAdvisor(
			TransactionAttributeSource transactionAttributeSource, TransactionInterceptor transactionInterceptor) {
		//创建BeanFactoryTransactionAttributeSourceAdvisor
		BeanFactoryTransactionAttributeSourceAdvisor advisor = new BeanFactoryTransactionAttributeSourceAdvisor();
		//设置属性源
		advisor.setTransactionAttributeSource(transactionAttributeSource);
		//设置通知
		advisor.setAdvice(transactionInterceptor);
		//设置@EnableTransactionManagement注解的order属性
		if (this.enableTx != null) {
			advisor.setOrder(this.enableTx.<Integer>getNumber("order"));
		}
		return advisor;
	}


	/**
	 * 注册一个事务属性源bean定义，名为"transactionAttributeSource"
	 * 类型就是AnnotationTransactionAttributeSource
	 */
	@Bean
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	public TransactionAttributeSource transactionAttributeSource() {
		return new AnnotationTransactionAttributeSource();
	}

	/**
	 * 注册一个事务拦截器bean定义，名为"transactionAttributeSource"
	 * 类型就是TransactionInterceptor
	 *
	 * @param transactionAttributeSource 事务属性源
	 */
	@Bean
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	public TransactionInterceptor transactionInterceptor(TransactionAttributeSource transactionAttributeSource) {
		TransactionInterceptor interceptor = new TransactionInterceptor();
		interceptor.setTransactionAttributeSource(transactionAttributeSource);
		//设置事务管理器，这个事务管理器是指通过TransactionManagementConfigurer配置的，而不是通过bean定义的，一般为null
		//所以说通过注解配置的TransactionInterceptor的transactionManager一般都为null，但是没关系，在使用时如果发现为null
		//Spring会查找容器中的TransactionManager的实现来作为事务管理器
		if (this.txManager != null) {
			interceptor.setTransactionManager(this.txManager);
		}
		return interceptor;
	}

}
