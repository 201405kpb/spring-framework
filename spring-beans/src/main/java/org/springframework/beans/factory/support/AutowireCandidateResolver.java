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

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.lang.Nullable;

/**
 * Strategy interface for determining whether a specific bean definition
 * qualifies as an autowire candidate for a specific dependency.
 * 用于确定特定beanDefinition是否有资格作为特定依赖项的自动装配候选的策略接口
 *
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @since 2.5
 */
public interface AutowireCandidateResolver {

	/**
	 * <p>确定给定的beanDefinition是否可以自动注入。只对@Autowired注解有效，配置文件中可以通过property显示注入</p>
	 * Determine whether the given bean definition qualifies as an
	 * autowire candidate for the given dependency.
	 * <p>确定给定的beanDefinition是否符合给定依赖项的自动装配候选条件。</p>
	 * <p>The default implementation checks
	 * {@link org.springframework.beans.factory.config.BeanDefinition#isAutowireCandidate()}.
	 * <p>默认实现{@link org.springframework.beans.factory.config.BeanDefinition#isAutowireCandidate()}</p>
	 * @param bdHolder the bean definition including bean name and aliases
	 *                 -- beanDefinition,包括bean名和别名封装对象
	 * @param descriptor the descriptor for the target method parameter or field
	 *                   -- 目标方法参数或字段的描述符
	 * @return whether the bean definition qualifies as autowire candidate
	 *   -- 给定的beanDefinition是否可以自动注入
	 * @see org.springframework.beans.factory.config.BeanDefinition#isAutowireCandidate()
	 */
	default boolean isAutowireCandidate(BeanDefinitionHolder bdHolder, DependencyDescriptor descriptor) {
		return bdHolder.getBeanDefinition().isAutowireCandidate();
	}

	/**
	 * <p>确定descriptor确实需要注入</p>
	 * Determine whether the given descriptor is effectively required.
	 * <p>确定是否确实需要给定的描述符。</p>
	 * <p>The default implementation checks {@link DependencyDescriptor#isRequired()}.
	 * <p>默认实现检查 {@link DependencyDescriptor#isRequired()}</p>
	 * @param descriptor the descriptor for the target method parameter or field
	 *                   -- 目标方法参数或字段描述符
	 * @return whether the descriptor is marked as required or possibly indicating
	 * non-required status some other way (e.g. through a parameter annotation)
	 * -- 描述符是否标记为必需，或者是否可能通过其他方式(例如通过参数注释)指示非必需状态
	 * @since 5.0
	 * @see DependencyDescriptor#isRequired()
	 */
	default boolean isRequired(DependencyDescriptor descriptor) {
		return descriptor.isRequired();
	}

	/**
	 * <p>表示desciptor有没有@Qualifier注解或qualifier标准修饰</p>
	 * Determine whether the given descriptor declares a qualifier beyond the type
	 * (typically - but not necessarily - a specific kind of annotation).
	 * <p>确定给定的描述符是否声明了类型之外的限定符(通常-但不一定-特定类型的注释)</p>
	 * <p>The default implementation returns {@code false}.
	 * <p>默认实现返回false</p>
	 * @param descriptor the descriptor for the target method parameter or field
	 *                   -- 目标方法参数或字段的描述符
	 * @return whether the descriptor declares a qualifier, narrowing the candidate
	 * status beyond the type match
	 *  -- 描述符是否声明限定词，将候选状态缩小到类型匹配之外
	 * @since 5.1
	 * @see org.springframework.beans.factory.annotation.QualifierAnnotationAutowireCandidateResolver#hasQualifier
	 */
	default boolean hasQualifier(DependencyDescriptor descriptor) {
		return false;
	}

	/**
	 * Determine whether a default value is suggested for the given dependency.
	 * <p>确定是否建议给定依赖项的默认值</p>
	 * <p>The default implementation simply returns {@code null}.
	 * <p>默认实现只是返回null</p>
	 * @param descriptor the descriptor for the target method parameter or field
	 *                   -- 目标方法参数或字段的描述符
	 * @return the value suggested (typically an expression String),
	 * or {@code null} if none found
	 *  -- 建议的值(通常是表达式字符串)，如果找不到，则为null
	 * @since 3.0
	 */
	@Nullable
	default Object getSuggestedValue(DependencyDescriptor descriptor) {
		return null;
	}

	/**
	 * Build a proxy for lazy resolution of the actual dependency target,
	 * if demanded by the injection point.
	 * <p>BeanDefinition描述了一个bean实例,该实例具有属性值，构造函数参数值以及
	 * 具体实现所提供的更多信息</p>
	 * <p>The default implementation simply returns {@code null}.
	 * <p>默认实现指示返回{@code null}</p>
	 * @param descriptor the descriptor for the target method parameter or field
	 *                   -- 目标方法参数或字段描述符
	 * @param beanName the name of the bean that contains the injection point
	 *                 -- 包含注入点的bean名
	 * @return the lazy resolution proxy for the actual dependency target,
	 * or {@code null} if straight resolution is to be performed
	 * -- 实际依赖关系目标的惰性解决方案代理；如果要执行直接解决方案，则为null
	 * @since 4.0
	 */
	@Nullable
	default Object getLazyResolutionProxyIfNecessary(DependencyDescriptor descriptor, @Nullable String beanName) {
		return null;
	}

	/**
	 * Determine the proxy class for lazy resolution of the dependency target,
	 * if demanded by the injection point.
	 * 如果注入点需要，则确定用于延迟解析依赖目标的代理类。
	 * <p>The default implementation simply returns {@code null}.
	 * <p>默认实现指示返回{@code null}</p>
	 * @param descriptor the descriptor for the target method parameter or field
	 *                      --目标方法参数或字段描述符
	 * @param beanName the name of the bean that contains the injection point
	 *                    --包含注入点的bean名
	 * @return the lazy resolution proxy class for the dependency target, if any
	 * 依赖目标的延迟解析代理类（如果有）
	 * @since 6.0
	 */
	@Nullable
	default Class<?> getLazyResolutionProxyClass(DependencyDescriptor descriptor, @Nullable String beanName) {
		return null;
	}

	/**
	 * Return a clone of this resolver instance if necessary, retaining its local
	 * configuration and allowing for the cloned instance to get associated with
	 * a new bean factory, or this original instance if there is no such state.
	 * <p>The default implementation creates a separate instance via the default
	 * class constructor, assuming no specific configuration state to copy.
	 * Subclasses may override this with custom configuration state handling
	 * or with standard {@link Cloneable} support (as implemented by Spring's
	 * own configurable {@code AutowireCandidateResolver} variants), or simply
	 * return {@code this} (as in {@link SimpleAutowireCandidateResolver}).
	 * <p>如果需要，返回此解析器实例的克隆，保留其本地配置，并允许克隆的实例与新的bean工厂关联，如果没有这样的状态，则返回此原始实例
	 * <p> 默认实现通过默认类构造函数创建一个单独的实例，假设没有要复制的特定配置状态。子类可以通过自定义配置状态处理或标准Cloneable支持AutowireCandidateResolver来覆盖此属性，
	 * 或者只返回this
	 * @since 5.2.7
	 * @see GenericTypeAwareAutowireCandidateResolver#cloneIfNecessary()
	 * @see DefaultListableBeanFactory#copyConfigurationFrom
	 */
	default AutowireCandidateResolver cloneIfNecessary() {
		return BeanUtils.instantiateClass(getClass());
	}

}
