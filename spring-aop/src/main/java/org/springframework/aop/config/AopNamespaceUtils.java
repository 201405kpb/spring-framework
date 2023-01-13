/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.aop.config;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.lang.Nullable;
import org.w3c.dom.Element;

/**
 * Utility class for handling registration of auto-proxy creators used internally
 * by the '{@code aop}' namespace tags.
 *
 * <p>Only a single auto-proxy creator should be registered and multiple configuration
 * elements may wish to register different concrete implementations. As such this class
 * delegates to {@link AopConfigUtils} which provides a simple escalation protocol.
 * Callers may request a particular auto-proxy creator and know that creator,
 * <i>or a more capable variant thereof</i>, will be registered as a post-processor.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @since 2.0
 * @see AopConfigUtils
 */
public abstract class AopNamespaceUtils {

	/**
	 * The {@code proxy-target-class} attribute as found on AOP-related XML tags.
	 * aop相关标签的proxy-target-class属性名常量，用于设置代理的模式
	 */
	public static final String PROXY_TARGET_CLASS_ATTRIBUTE = "proxy-target-class";

	/**
	 * The {@code expose-proxy} attribute as found on AOP-related XML tags.
	 * aop相关标签的expose-proxy属性名常量，用于设置是否暴露代理对象
	 */
	private static final String EXPOSE_PROXY_ATTRIBUTE = "expose-proxy";


	/**
	 * 如有必要，注册一个InfrastructureAdvisorAutoProxyCreator类型的AutoProxyCreator
	 * <p>
	 * 该方法的源码与我们在此前的<aop:config/>标签解析部分讲过的registerAspectJAutoProxyCreatorIfNecessary方法基本一致
	 * 只不过AutoProxyCreator的类型变成了InfrastructureAdvisorAutoProxyCreator
	 */
	public static void registerAutoProxyCreatorIfNecessary(
			ParserContext parserContext, Element sourceElement) {
		/*
		 * 1 尝试注册或者升级一个名为"org.springframework.aop.config.internalAutoProxyCreator"
		 * 类型为InfrastructureAdvisorAutoProxyCreator的自动代理创建者的bean定义
		 */
		BeanDefinition beanDefinition = AopConfigUtils.registerAutoProxyCreatorIfNecessary(
				parserContext.getRegistry(), parserContext.extractSource(sourceElement));
		/*
		 * 2 解析proxy-target-class与expose-proxy属性
		 * proxy-target-class用于设置代理模式，默认是优先JDK动态代理，其次CGLIB代理，可以指定为CGLIB代理
		 * expose-proxy用于暴露代理对象，主要用来解决同一个目标类的方法互相调用时代理不生效的问题
		 */
		useClassProxyingIfNecessary(parserContext.getRegistry(), sourceElement);
		/*
		 * 3 注册组件，这里的注册是指存放到外层方法新建的CompositeComponentDefinition对象的内部集合中或者广播事件，而不是注册到注册表中
		 */
		registerComponentIfNecessary(beanDefinition, parserContext);
	}


	/**
	 * 注册名为org.springframework.aop.config.internalAutoProxyCreator的beanDefinition，
	 * 其中的class类为AspectJAwareAdvisorAutoProxyCreator，其也会被注册到bean工厂中。
	 * AspectJAwareAdvisorAutoProxyCreator用于支持AspectJ方式的自动代理
	 * 如果proxy-target-class=true，强制使用代理。会将proxyTargetClass保存到definition
	 * @param parserContext
	 * @param sourceElement
	 */
	public static void registerAspectJAutoProxyCreatorIfNecessary(
			ParserContext parserContext, Element sourceElement) {
		/*
		 * 尝试注册或者升级一个名为"org.springframework.aop.config.internalAutoProxyCreator"
		 * 类型为AspectJAwareAdvisorAutoProxyCreator的自动代理创建者的bean定义
		 */
		BeanDefinition beanDefinition = AopConfigUtils.registerAspectJAutoProxyCreatorIfNecessary(
				parserContext.getRegistry(), parserContext.extractSource(sourceElement));
		/*
		 * 解析proxy-target-class与expose-proxy属性
		 * proxy-target-class用于设置代理模式，默认是优先JDK动态代理，其次CGLIB代理，可以指定为CGLIB代理
		 * expose-proxy用于暴露代理对象，主要用来解决同一个目标类的方法互相调用时代理不生效的问题
		 */
		useClassProxyingIfNecessary(parserContext.getRegistry(), sourceElement);
		/*
		 * 注册组件，这里的注册是指存放到外层方法新建的CompositeComponentDefinition对象的内部集合中或者广播事件，而不是注册到注册表中
		 */
		registerComponentIfNecessary(beanDefinition, parserContext);
	}

	/**
	 * 如有必要，注册AnnotationAwareAspectJAutoProxyCreator
	 * @param parserContext
	 * @param sourceElement
	 */
	public static void registerAspectJAnnotationAutoProxyCreatorIfNecessary(
			ParserContext parserContext, Element sourceElement) {
		/*
		 * 1 尝试注册或者升级一个名为"org.springframework.aop.config.internalAutoProxyCreator"
		 * 类型为AnnotationAwareAspectJAutoProxyCreator的自动代理创建者的bean定义
		 */
		BeanDefinition beanDefinition = AopConfigUtils.registerAspectJAnnotationAutoProxyCreatorIfNecessary(
				parserContext.getRegistry(), parserContext.extractSource(sourceElement));
		/*
		 * 2 尝试解析<aop:aspectj-autoproxy/>标签的proxy-target-class与expose-proxy属性
		 * proxy-target-class用于设置代理模式，默认是优先JDK动态代理，其次CGLIB代理，可以指定为CGLIB代理
		 * expose-proxy用于暴露代理对象，主要用来解决同一个目标类的方法互相调用时代理不生效的问题
		 */
		useClassProxyingIfNecessary(parserContext.getRegistry(), sourceElement);
		/*
		 * 3 注册组件，这里的注册是指存放到外层新建的CompositeComponentDefinition对象的内部集合中或者广播事件，而不是注册到注册表中
		 */
		registerComponentIfNecessary(beanDefinition, parserContext);
	}

	/**
	 * 解析设置proxy-target-class和expose-proxy属性
	 * @param registry 注册表
	 * @param sourceElement 标签元素
	 */
	private static void useClassProxyingIfNecessary(BeanDefinitionRegistry registry, @Nullable Element sourceElement) {
		if (sourceElement != null) {
			//获取proxy-target-class属性值，默认false，即优先采用JDK动态代理，不满足则采用CGLIB代理
			boolean proxyTargetClass = Boolean.parseBoolean(sourceElement.getAttribute(PROXY_TARGET_CLASS_ATTRIBUTE));
			//如果设置为true，那么强制走CGLIB代理
			if (proxyTargetClass) {
				AopConfigUtils.forceAutoProxyCreatorToUseClassProxying(registry);
			}
			//获取expose-proxy属性值，默认false，即不暴露代理对象
			boolean exposeProxy = Boolean.parseBoolean(sourceElement.getAttribute(EXPOSE_PROXY_ATTRIBUTE));
			//如果设置为true，那么暴露代理对象
			if (exposeProxy) {
				AopConfigUtils.forceAutoProxyCreatorToExposeProxy(registry);
			}
		}
	}

	/**
	 * 如果bean定义不为null，那么注册组件,实际上是存入parserContext的containingComponents集合的栈顶元素的内部集合中或者广播事件
	 * @param beanDefinition bean 定义
	 * @param parserContext 上下文
	 */
	private static void registerComponentIfNecessary(@Nullable BeanDefinition beanDefinition, ParserContext parserContext) {
		if (beanDefinition != null) {
			//实际上是存入parserContext的containingComponents集合的栈顶元素的内部集合中
			parserContext.registerComponent(
					new BeanComponentDefinition(beanDefinition, AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME));
		}
	}

}
