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

import java.util.Collection;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportAware;
import org.springframework.context.annotation.Role;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.lang.Nullable;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.config.TransactionManagementConfigUtils;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.util.CollectionUtils;

/**
 * Abstract base {@code @Configuration} class providing common structure for enabling
 * Spring's annotation-driven transaction management capability.
 *
 * @author Chris Beams
 * @author Stephane Nicoll
 * @since 3.1
 * @see EnableTransactionManagement
 */
@Configuration
public abstract class AbstractTransactionManagementConfiguration implements ImportAware {

	@Nullable
	protected AnnotationAttributes enableTx;

	/**
	 * 通过TransactionManagementConfigurer配置的默认事务管理器，可以为null
	 */
	@Nullable
	protected TransactionManager txManager;

	/**
	 * 自动回调方法
	 * 用于获取@EnableTransactionManagement注解的属性
	 *
	 * @param importMetadata 引入当前类的类元数据，就是标注有@EnableTransactionManagement注解的类
	 */
	@Override
	public void setImportMetadata(AnnotationMetadata importMetadata) {
		//获取引入当前配置类的类上面的@EnableTransactionManagement注解的属性
		this.enableTx = AnnotationAttributes.fromMap(
				importMetadata.getAnnotationAttributes(EnableTransactionManagement.class.getName(), false));
		//没有就抛出异常
		if (this.enableTx == null) {
			throw new IllegalArgumentException(
					"@EnableTransactionManagement is not present on importing class " + importMetadata.getClassName());
		}
	}

	/**
	 * 期望通过TransactionManagementConfigurer的方式注入事务管理器
	 * 由于@Autowired的required属性为false，没有TransactionManagementConfigurer的bean的时候，将不会执行该方法
	 */
	@Autowired(required = false)
	void setConfigurers(Collection<TransactionManagementConfigurer> configurers) {
		//没有就返回
		if (CollectionUtils.isEmpty(configurers)) {
			return;
		}
		//超过一个事务管理器配置类就抛出异常
		if (configurers.size() > 1) {
			throw new IllegalStateException("Only one TransactionManagementConfigurer may exist");
		}
		//从事务管理器配置类中获取事务管理器
		TransactionManagementConfigurer configurer = configurers.iterator().next();
		this.txManager = configurer.annotationDrivenTransactionManager();
	}


	/**
	 * 注册一个事务事件监听器工厂的bean定义，名为"org.springframework.transaction.config.internalTransactionalEventListenerFactory"
	 * 类型就是TransactionalEventListenerFactory
	 * <p>
	 * 该工厂可以将@TransactionalEventListener注解方法解析为一个ApplicationListenerMethodTransactionalAdapter类型的事件监听器，用于支持事务事件的监听。
	 */
	@Bean(name = TransactionManagementConfigUtils.TRANSACTIONAL_EVENT_LISTENER_FACTORY_BEAN_NAME)
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	public static TransactionalEventListenerFactory transactionalEventListenerFactory() {
		return new TransactionalEventListenerFactory();
	}
}