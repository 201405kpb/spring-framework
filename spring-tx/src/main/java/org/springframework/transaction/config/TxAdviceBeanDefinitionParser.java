/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.transaction.config;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.w3c.dom.Element;

import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource;
import org.springframework.transaction.interceptor.NoRollbackRuleAttribute;
import org.springframework.transaction.interceptor.RollbackRuleAttribute;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;

/**
 * {@link org.springframework.beans.factory.xml.BeanDefinitionParser
 * BeanDefinitionParser} for the {@code <tx:advice/>} tag.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Adrian Colyer
 * @author Chris Beams
 * @since 2.0
 */
class TxAdviceBeanDefinitionParser extends AbstractSingleBeanDefinitionParser {

	private static final String METHOD_ELEMENT = "method";

	private static final String METHOD_NAME_ATTRIBUTE = "name";

	private static final String ATTRIBUTES_ELEMENT = "attributes";

	private static final String TIMEOUT_ATTRIBUTE = "timeout";

	private static final String READ_ONLY_ATTRIBUTE = "read-only";

	private static final String PROPAGATION_ATTRIBUTE = "propagation";

	private static final String ISOLATION_ATTRIBUTE = "isolation";

	private static final String ROLLBACK_FOR_ATTRIBUTE = "rollback-for";

	private static final String NO_ROLLBACK_FOR_ATTRIBUTE = "no-rollback-for";


	@Override
	protected Class<?> getBeanClass(Element element) {
		return TransactionInterceptor.class;
	}


	/**
	 * 解析<tx:advice/>标签的自有属性和子标签
	 *
	 * @param element       当前<tx:advice/>标签元素
	 * @param parserContext 解析上下文，包含一些常用的属性
	 * @param builder       bean定义的构建者，TransactionInterceptor类型
	 */
	@Override
	protected void doParse(Element element, ParserContext parserContext, BeanDefinitionBuilder builder) {
		/*
		 * 设置transactionManager属性，即设置与当前advice关联的TransactionManager的beanName
		 * 如果没有设置"transaction-manager"属性，则默认设置为"transactionManager"
		 */
		builder.addPropertyReference("transactionManager", TxNamespaceHandler.getTransactionManagerName(element));

		/*
		 * 解析<tx:advice/>标签内部的<tx:attributes/>子标签
		 * <tx:attributes/>子标签可以定义方法和事务属性的映射
		 *
		 * 实际会设置一个transactionAttributeSource属性，值为一个RootBeanDefinition，class类型为TransactionAttributeSource
		 * TransactionAttributeSource类似于TargetSource，内部包装了一个或多个TransactionAttribute
		 * 而TransactionAttribute又是此前学习过的TransactionDefinition的子接口
		 *
		 * 因此TransactionAttribute内部同样定义了事务的一系列基础属性，内部具有获取各种Spring事务属性的方法
		 * 比如Propagation、Isolation、Timeout、Read-only status等等属性
		 */
		//获取<tx:advice/>标签内部的所有<tx:attributes/>子标签集合
		List<Element> txAttributes = DomUtils.getChildElementsByTagName(element, ATTRIBUTES_ELEMENT);
		//如果具有超过一个该类型的子标签，则抛出异常:Element <attributes> is allowed at most once inside element <advice>
		//这说明一个<tx:advice/>标签内部的只能由一个<tx:attributes/>子标签
		if (txAttributes.size() > 1) {
			parserContext.getReaderContext().error(
					"Element <attributes> is allowed at most once inside element <advice>", element);
		}
		//如果具有一个该类型的子标签，则解析该标签
		else if (txAttributes.size() == 1) {
			//获取该标签
			Element attributeSourceElement = txAttributes.get(0);
			/*
			 * 解析为一个bean定义，class类型为NameMatchTransactionAttributeSource类型
			 * 该事务属性源可以根据方法名匹配，然后将事务的属性作用在匹配的方法上，主要用于支持基于XML配置的事务管理的事务属性源
			 */
			RootBeanDefinition attributeSourceDefinition = parseAttributeSource(attributeSourceElement, parserContext);
			//设置为transactionAttributeSource属性
			builder.addPropertyValue("transactionAttributeSource", attributeSourceDefinition);
		}
		//如果没有设置该类型的子标签
		else {
			/*
			 * 那么同样会设置transactionAttributeSource属性，bean定义的class类型默认为AnnotationTransactionAttributeSource类型
			 * 该事务属性源是基于注解驱动的事务管理的事务属性源，可以解析@Transactional事务注解
			 */
			builder.addPropertyValue("transactionAttributeSource",
					new RootBeanDefinition("org.springframework.transaction.annotation.AnnotationTransactionAttributeSource"));
		}
	}

	/**
	 * 解析属性源
	 *
	 * @param attrEle       <tx:attributes/>子标签元素
	 * @param parserContext 解析上下文
	 * @return 解析结果，一个bean定义
	 */
	private RootBeanDefinition parseAttributeSource(Element attrEle, ParserContext parserContext) {
		//获取<tx:attributes/>标签内部的<tx:method>子标签集合
		List<Element> methods = DomUtils.getChildElementsByTagName(attrEle, METHOD_ELEMENT);
		//建立一个map，即这些<tx:method>子标签集合将会统一解析为一个map属性
		ManagedMap<TypedStringValue, RuleBasedTransactionAttribute> transactionAttributeMap =
				new ManagedMap<>(methods.size());
		transactionAttributeMap.setSource(parserContext.extractSource(attrEle));
		//解析每一个<tx:method>子标签
		for (Element methodEle : methods) {
			//获取name属性
			String name = methodEle.getAttribute(METHOD_NAME_ATTRIBUTE);
			TypedStringValue nameHolder = new TypedStringValue(name);
			nameHolder.setSource(parserContext.extractSource(methodEle));
			/*
			 * 创建一个RuleBasedTransactionAttribute，它是TransactionAttribute的实现并扩展了DefaultTransactionAttribute
			 * 该实现实际上就是对rollback-for和no-rollback-for属性的支持，可以自定义回滚与不回滚的规则。
			 * 如果没有指定相关规则，那么和DefaultTransactionAttribute一样，将会在抛出RuntimeException时回滚
			 */
			RuleBasedTransactionAttribute attribute = new RuleBasedTransactionAttribute();
			//传播行为
			String propagation = methodEle.getAttribute(PROPAGATION_ATTRIBUTE);
			//隔离级别
			String isolation = methodEle.getAttribute(ISOLATION_ATTRIBUTE);
			//超时时间
			String timeout = methodEle.getAttribute(TIMEOUT_ATTRIBUTE);
			//是否只读
			String readOnly = methodEle.getAttribute(READ_ONLY_ATTRIBUTE);
			if (StringUtils.hasText(propagation)) {
				//设置传播行为属性，在XML中设置的是传播行为常量名的字符串后缀，这里会加上传播行为常量名的前缀
				//最终会按照TransactionDefinition中定义的传播行为常量名称获取、设置对应的int值
				//默认事务传播行为是PROPAGATION_REQUIRED，即加入到当前事务或者开始新事物
				attribute.setPropagationBehaviorName(RuleBasedTransactionAttribute.PREFIX_PROPAGATION + propagation);
			}
			if (StringUtils.hasText(isolation)) {
				//设置隔离级别属性，在XML中设置的是隔离级别常量名的字符串后缀，这里会加上隔离级别常量名的前缀
				//最终会按照TransactionDefinition中定义的隔离级别常量名称获取、设置对应的int值
				//默认事务隔离级别是ISOLATION_DEFAULT，即采用连接的数据的隔离级别
				attribute.setIsolationLevelName(RuleBasedTransactionAttribute.PREFIX_ISOLATION + isolation);
			}
			if (StringUtils.hasText(timeout)) {
				try {
					//设置超时时间属性，必须是int类型的常量，单位秒
					//默认超时时间为TIMEOUT_DEFAULT，即不超时
					attribute.setTimeout(Integer.parseInt(timeout));
				} catch (NumberFormatException ex) {
					parserContext.getReaderContext().error("Timeout must be an integer value: [" + timeout + "]", methodEle);
				}
			}
			if (StringUtils.hasText(readOnly)) {
				//设置是否只读
				//默认false，即读写
				attribute.setReadOnly(Boolean.parseBoolean(methodEle.getAttribute(READ_ONLY_ATTRIBUTE)));
			}
			//回滚规则列表
			List<RollbackRuleAttribute> rollbackRules = new LinkedList<>();
			//如果存在rollback-for属性
			if (methodEle.hasAttribute(ROLLBACK_FOR_ATTRIBUTE)) {
				//获取该属性
				String rollbackForValue = methodEle.getAttribute(ROLLBACK_FOR_ATTRIBUTE);
				//设置回滚规则
				addRollbackRuleAttributesTo(rollbackRules, rollbackForValue);
			}
			//如果存在no-rollback-for属性
			if (methodEle.hasAttribute(NO_ROLLBACK_FOR_ATTRIBUTE)) {
				//获取该属性
				String noRollbackForValue = methodEle.getAttribute(NO_ROLLBACK_FOR_ATTRIBUTE);
				//设置不回滚规则，它们在回滚规则之后设置
				//因此如果rollback-for和no-rollback-for设置了相同的异常，那么最终会会回滚
				addNoRollbackRuleAttributesTo(rollbackRules, noRollbackForValue);
			}
			//设置回滚规则，这就是RuleBasedTransactionAttribute的特性
			attribute.setRollbackRules(rollbackRules);
			//存入map中，key为name属性（方法名），value为RuleBasedTransactionAttribute类型的事务属性
			transactionAttributeMap.put(nameHolder, attribute);
		}
		//新建RootBeanDefinition，class为NameMatchTransactionAttributeSource
		//也就是根据方法名匹配，然后该事务属性就会作用在对应的方法上。简单的说就是XML的事务配置的支持
		RootBeanDefinition attributeSourceDefinition = new RootBeanDefinition(NameMatchTransactionAttributeSource.class);
		attributeSourceDefinition.setSource(parserContext.extractSource(attrEle));
		//设置nameMap属性，就是我们刚才解析的<tx:method>子标签集合
		attributeSourceDefinition.getPropertyValues().add("nameMap", transactionAttributeMap);
		return attributeSourceDefinition;
	}

	/**
	 * 设置回滚规则属性
	 *
	 * @param rollbackRules    回滚规则列表
	 * @param rollbackForValue rollback-for回滚属性值
	 */
	private void addRollbackRuleAttributesTo(List<RollbackRuleAttribute> rollbackRules, String rollbackForValue) {
		//根据","字符串拆分为数组
		String[] exceptionTypeNames = StringUtils.commaDelimitedListToStringArray(rollbackForValue);
		for (String typeName : exceptionTypeNames) {
			//添加RollbackRuleAttribute
			rollbackRules.add(new RollbackRuleAttribute(typeName.strip()));
		}
	}

	/**
	 * 设置不回滚规则属性
	 * @param rollbackRules      回滚规则列表
	 * @param noRollbackForValue no-rollback-for不回滚属性值
	 */
	private void addNoRollbackRuleAttributesTo(List<RollbackRuleAttribute> rollbackRules, String noRollbackForValue) {
		//根据","字符串拆分为数组
		String[] exceptionTypeNames = StringUtils.commaDelimitedListToStringArray(noRollbackForValue);
		for (String typeName : exceptionTypeNames) {
			//添加NoRollbackRuleAttribute，它们在回滚规则之后设置
			rollbackRules.add(new NoRollbackRuleAttribute(typeName.strip()));
		}
	}

}
