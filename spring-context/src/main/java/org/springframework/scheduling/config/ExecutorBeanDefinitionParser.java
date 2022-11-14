/*
 * Copyright 2002-2016 the original author or authors.
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

import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.util.StringUtils;

/**
 * Parser for the 'executor' element of the 'task' namespace.
 *
 * @author Mark Fisher
 * @author Juergen Hoeller
 * @since 3.0
 */
public class ExecutorBeanDefinitionParser extends AbstractSingleBeanDefinitionParser {

	@Override
	protected String getBeanClassName(Element element) {
		return "org.springframework.scheduling.config.TaskExecutorFactoryBean";
	}

	/**
	 * 解析<task:executor/>标签的自有属性
	 *
	 * @param element       当前<task:executor/>标签元素
	 * @param parserContext 解析上下文.,包含各种常用变量
	 * @param builder       bean定义构建者
	 */
	@Override
	protected void doParse(Element element, ParserContext parserContext, BeanDefinitionBuilder builder) {
		//设置keepAliveSeconds属性
		String keepAliveSeconds = element.getAttribute("keep-alive");
		if (StringUtils.hasText(keepAliveSeconds)) {
			builder.addPropertyValue("keepAliveSeconds", keepAliveSeconds);
		}
		//设置queueCapacity属性
		String queueCapacity = element.getAttribute("queue-capacity");
		if (StringUtils.hasText(queueCapacity)) {
			builder.addPropertyValue("queueCapacity", queueCapacity);
		}
		//设置rejectedExecutionHandler拒绝策略属性
		configureRejectionPolicy(element, builder);
		//设置poolSize属性
		String poolSize = element.getAttribute("pool-size");
		if (StringUtils.hasText(poolSize)) {
			builder.addPropertyValue("poolSize", poolSize);
		}
	}

	private void configureRejectionPolicy(Element element, BeanDefinitionBuilder builder) {
		//获取设置的拒绝策略字符串
		String rejectionPolicy = element.getAttribute("rejection-policy");
		//如果没有设置，那么就不配置拒绝策略
		if (!StringUtils.hasText(rejectionPolicy)) {
			return;
		}
		//Java提供的4种基本拒绝策略的前缀包路径
		String prefix = "java.util.concurrent.ThreadPoolExecutor.";
		String policyClassName;
		//如果值为ABORT，那么拒绝策略就是AbortPolicy
		if (rejectionPolicy.equals("ABORT")) {
			policyClassName = prefix + "AbortPolicy";
		}
		//如果值为CALLER_RUNS，那么拒绝策略就是CallerRunsPolicy
		else if (rejectionPolicy.equals("CALLER_RUNS")) {
			policyClassName = prefix + "CallerRunsPolicy";
		}
		//如果值为DISCARD，那么拒绝策略就是DiscardPolicy
		else if (rejectionPolicy.equals("DISCARD")) {
			policyClassName = prefix + "DiscardPolicy";
		}
		//如果值为DISCARD_OLDEST，那么拒绝策略就是DiscardOldestPolicy
		else if (rejectionPolicy.equals("DISCARD_OLDEST")) {
			policyClassName = prefix + "DiscardOldestPolicy";
		}
		//如果值是其他字符串，那么直接采用该字符串
		else {
			policyClassName = rejectionPolicy;
		}
		//设置rejectedExecutionHandler拒绝策略属性
		builder.addPropertyValue("rejectedExecutionHandler", new RootBeanDefinition(policyClassName));
	}

}
