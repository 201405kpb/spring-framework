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

package org.springframework.beans.factory.support;

import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.lang.Nullable;

/**
 * {@link AutowireCandidateResolver} implementation to use when no annotation
 * support is available. This implementation checks the bean definition only.
 * 没有注释支持可用时使用的AutowireCandidateResolver实现。此实现仅检查bean定义
 *
 * @author Mark Fisher
 * @author Juergen Hoeller
 * @since 2.5
 */
public class SimpleAutowireCandidateResolver implements AutowireCandidateResolver {

	/**
	 * Shared instance of {@code SimpleAutowireCandidateResolver}.
	 * @since 5.2.7
	 */
	public static final SimpleAutowireCandidateResolver INSTANCE = new SimpleAutowireCandidateResolver();


	/**
	 * 确定给定的beanDefinition是否可以自动注入。只对@Autowired注解有效，配置文件中可以通过property显示注入
	 * <p>简单返回bdHolder的BeanDefinition对象的是否可以自动注入标记结果【{@link AbstractBeanDefinition#isAutowireCandidate()}】</p>
	 * @param bdHolder the bean definition including bean name and aliases
	 *                 -- beanDefinition,包括bean名和别名封装对象
	 * @param descriptor the descriptor for the target method parameter or field
	 *                   -- 目标方法参数或字段的描述符
	 * @return  给定的beanDefinition是否可以自动注入
	 */
	@Override
	public boolean isAutowireCandidate(BeanDefinitionHolder bdHolder, DependencyDescriptor descriptor) {
		return bdHolder.getBeanDefinition().isAutowireCandidate();
	}

	/**
	 * 确定descriptor是否需要依赖项(descriptor是否设置了需要依赖项)。
	 * @param descriptor the descriptor for the target method parameter or field
	 *                   -- 目标方法参数或字段描述符
	 */
	@Override
	public boolean isRequired(DependencyDescriptor descriptor) {
		return descriptor.isRequired();
	}

	@Override
	public boolean hasQualifier(DependencyDescriptor descriptor) {
		return false;
	}

	@Override
	@Nullable
	public Object getSuggestedValue(DependencyDescriptor descriptor) {
		return null;
	}

	@Override
	@Nullable
	public Object getLazyResolutionProxyIfNecessary(DependencyDescriptor descriptor, @Nullable String beanName) {
		return null;
	}

	@Override
	@Nullable
	public Class<?> getLazyResolutionProxyClass(DependencyDescriptor descriptor, @Nullable String beanName) {
		return null;
	}

	/**
	 * This implementation returns {@code this} as-is.
	 * @see #INSTANCE
	 */
	@Override
	public AutowireCandidateResolver cloneIfNecessary() {
		return this;
	}

}
