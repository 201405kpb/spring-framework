/*
 * Copyright 2002-2015 the original author or authors.
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

package org.springframework.scheduling.config;

import org.w3c.dom.Element;

import org.springframework.aop.config.AopNamespaceUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.parsing.CompositeComponentDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * Parser for the 'annotation-driven' element of the 'task' namespace.
 *
 * @author Mark Fisher
 * @author Juergen Hoeller
 * @author Ramnivas Laddad
 * @author Chris Beams
 * @author Stephane Nicoll
 * @since 3.0
 */
public class AnnotationDrivenBeanDefinitionParser implements BeanDefinitionParser {

	private static final String ASYNC_EXECUTION_ASPECT_CLASS_NAME =
			"org.springframework.scheduling.aspectj.AnnotationAsyncExecutionAspect";


	/**
	 * 解析<task:annotation-driven/>标签元素，尝试注册两个bean定义
	 * <p>
	 * 一个名为"org.springframework.context.annotation.internalAsyncAnnotationProcessor"的AsyncAnnotationBeanPostProcessor
	 * 一个名为"org.springframework.context.annotation.internalScheduledAnnotationProcessor"的ScheduledAnnotationBeanPostProcessor
	 * <p>
	 * 他们都是BeanPostProcessor的实现，将会在普通bean实例化之前被实例化，并且在普通bean实例化之后用于判断该bean是否需要进行代理以及进行代理对象的创建工作
	 * 也就是说，这真的代理对象的创建是靠这两个后处理器来完成的，<task:annotation-driven/>标签仅仅是解析属性并注册这两个后处理器而已！
	 *
	 * @param element       the element that is to be parsed into one or more {@link BeanDefinition BeanDefinitions}
	 *                      当前<task:annotation-driven/>标签元素
	 * @param parserContext the object encapsulating the current state of the parsing process;
	 *                      provides access to a {@link org.springframework.beans.factory.support.BeanDefinitionRegistry}
	 *                      解析上下文，从中可以获取各种配置
	 * @return 返回 db
	 */
	@Override
	@Nullable
	public BeanDefinition parse(Element element, ParserContext parserContext) {
		//获取源
		Object source = parserContext.extractSource(element);

		// Register component for the surrounding <task:annotation-driven> element.
		//为<task:annotation-driven> 标签注册一个CompositeComponentDefinition类型的bean定义组件
		//该类型的bean定义，内部可以包含多个bean定义，因此又称为复合bean定义
		CompositeComponentDefinition compDefinition = new CompositeComponentDefinition(element.getTagName(), source);
		//存入解析上下文内部的containingComponents集合中，入栈顶
		parserContext.pushContainingComponent(compDefinition);

		// Nest the concrete post-processor bean in the surrounding component.
		//获取上下文中保存的当前容器的注册表
		BeanDefinitionRegistry registry = parserContext.getRegistry();

		//获取mode属性
		String mode = element.getAttribute("mode");
		if ("aspectj".equals(mode)) {
			// mode="aspectj"
			//如果设置为aspectj，那么注册一个Aspect异步执行切面的 bean 。
			registerAsyncExecutionAspect(element, parserContext);
		} else {
			// mode="proxy"
			//如果设置为proxy，那么采用Spring AOP来进行代理
			//注册用于Async异步任务的bean后处理器AsyncAnnotationBeanPostProcessor
			//如果bean注册表中已经包含了名为"org.springframework.context.annotation.internalAsyncAnnotationProcessor"的bean定义
			if (registry.containsBeanDefinition(TaskManagementConfigUtils.ASYNC_ANNOTATION_PROCESSOR_BEAN_NAME)) {
				//那么直接抛出异常，表示可能出现了多个AsyncAnnotationBeanPostProcessor
				parserContext.getReaderContext().error(
						"Only one AsyncAnnotationBeanPostProcessor may exist within the context.", source);
			}
			//否则，注册一个名为"org.springframework.context.annotation.internalAsyncAnnotationProcessor"的bean定义
			else {
				//获取bean定义构建者，设置beanClassName为"org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor"
				//AsyncAnnotationBeanPostProcessor实际上是一个bean后处理器
				BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(
						"org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor");
				builder.getRawBeanDefinition().setSource(source);

				/*解析标签的executor属性，并设置为要创建的bean定义的executor属性*/
				String executor = element.getAttribute("executor");
				if (StringUtils.hasText(executor)) {
					builder.addPropertyReference("executor", executor);
				}
				/*解析标签的exception-handler属性，并设置为要创建的bean定义的exceptionHandler属性*/
				String exceptionHandler = element.getAttribute("exception-handler");
				if (StringUtils.hasText(exceptionHandler)) {
					builder.addPropertyReference("exceptionHandler", exceptionHandler);
				}
				/*解析标签的proxy-target-class属性，并设置为要创建的bean定义的proxyTargetClass属性*/
				if (Boolean.parseBoolean(element.getAttribute(AopNamespaceUtils.PROXY_TARGET_CLASS_ATTRIBUTE))) {
					builder.addPropertyValue("proxyTargetClass", true);
				}
				/*
				 * 注册bean后处理器到注册表中，名为"org.springframework.context.annotation.internalAsyncAnnotationProcessor"
				 */
				registerPostProcessor(parserContext, builder, TaskManagementConfigUtils.ASYNC_ANNOTATION_PROCESSOR_BEAN_NAME);
			}
		}

		/*
		 * 注册用于Scheduled调度任务的bean后处理器ScheduledAnnotationBeanPostProcessor
		 */
		//如果注册表包含名为"org.springframework.context.annotation.internalScheduledAnnotationProcessor"的bean定义
		if (registry.containsBeanDefinition(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)) {
			//那么直接抛出异常，表示可能出现了多个ScheduledAnnotationBeanPostProcessor
			parserContext.getReaderContext().error(
					"Only one ScheduledAnnotationBeanPostProcessor may exist within the context.", source);
		}
		//否则，注册一个名为"org.springframework.context.annotation.internalScheduledAnnotationProcessor"的bean定义
		else {
			//获取bean定义构建者，设置beanClassName为"org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor"
			//ScheduledAnnotationBeanPostProcessor实际上是一个bean后处理器
			BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(
					"org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor");
			builder.getRawBeanDefinition().setSource(source);
			/*解析标签的scheduler属性，并设置为要创建的bean定义的scheduler属性*/
			String scheduler = element.getAttribute("scheduler");
			if (StringUtils.hasText(scheduler)) {
				builder.addPropertyReference("scheduler", scheduler);
			}
			/*
			 * 注册bean后处理器到注册表中，名为"org.springframework.context.annotation.internalScheduledAnnotationProcessor"
			 */
			registerPostProcessor(parserContext, builder, TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
		}

		// Finally register the composite component.
		//出栈并注册，并不是注册到注册表中……可能发布事件或者什么也不做
		parserContext.popAndRegisterContainingComponent();

		return null;
	}

	private void registerAsyncExecutionAspect(Element element, ParserContext parserContext) {
		if (!parserContext.getRegistry().containsBeanDefinition(TaskManagementConfigUtils.ASYNC_EXECUTION_ASPECT_BEAN_NAME)) {
			BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(ASYNC_EXECUTION_ASPECT_CLASS_NAME);
			builder.setFactoryMethod("aspectOf");
			String executor = element.getAttribute("executor");
			if (StringUtils.hasText(executor)) {
				builder.addPropertyReference("executor", executor);
			}
			String exceptionHandler = element.getAttribute("exception-handler");
			if (StringUtils.hasText(exceptionHandler)) {
				builder.addPropertyReference("exceptionHandler", exceptionHandler);
			}
			parserContext.registerBeanComponent(new BeanComponentDefinition(builder.getBeanDefinition(),
					TaskManagementConfigUtils.ASYNC_EXECUTION_ASPECT_BEAN_NAME));
		}
	}

	/**
	 * 尝试注册bean后处理器
	 *
	 * @param parserContext 解析上下文
	 * @param builder       bean定义构建者
	 * @param beanName      注册的beanName
	 */
	private static void registerPostProcessor(
			ParserContext parserContext, BeanDefinitionBuilder builder, String beanName) {
		builder.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
		//调用registerBeanDefinition方法注册bean定义，该方法我们在此前就讲过了
		parserContext.getRegistry().registerBeanDefinition(beanName, builder.getBeanDefinition());
		//获取bdholder
		BeanDefinitionHolder holder = new BeanDefinitionHolder(builder.getBeanDefinition(), beanName);
		//注册组件，并不是注册到注册表中……而是发布组件注册事件，可以被监听到
		parserContext.registerComponent(new BeanComponentDefinition(holder));
	}

}
