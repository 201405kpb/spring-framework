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
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.PatternMatchUtils;
import org.springframework.util.StringValueResolver;

/**
 * Simple {@link TransactionAttributeSource} implementation that
 * allows attributes to be matched by registered name.
 *
 * @author Juergen Hoeller
 * @since 21.08.2003
 * @see #isMatch
 * @see MethodMapTransactionAttributeSource
 */
@SuppressWarnings("serial")
public class NameMatchTransactionAttributeSource
		implements TransactionAttributeSource, EmbeddedValueResolverAware, InitializingBean, Serializable {

	/**
	 * Logger available to subclasses.
	 * <p>Static for optimal serialization.
	 */
	protected static final Log logger = LogFactory.getLog(NameMatchTransactionAttributeSource.class);

	/** Keys are method names; values are TransactionAttributes. */
	private final Map<String, TransactionAttribute> nameMap = new HashMap<>();

	@Nullable
	private StringValueResolver embeddedValueResolver;


	/**
	 * Set a name/attribute map, consisting of method names
	 * (e.g. "myMethod") and {@link TransactionAttribute} instances.
	 * @see #setProperties
	 * @see TransactionAttribute
	 */
	public void setNameMap(Map<String, TransactionAttribute> nameMap) {
		nameMap.forEach(this::addTransactionalMethod);
	}

	/**
	 * Parse the given properties into a name/attribute map.
	 * <p>Expects method names as keys and String attributes definitions as values,
	 * parsable into {@link TransactionAttribute} instances via a
	 * {@link TransactionAttributeEditor}.
	 * @see #setNameMap
	 * @see TransactionAttributeEditor
	 */
	public void setProperties(Properties transactionAttributes) {
		TransactionAttributeEditor tae = new TransactionAttributeEditor();
		Enumeration<?> propNames = transactionAttributes.propertyNames();
		while (propNames.hasMoreElements()) {
			String methodName = (String) propNames.nextElement();
			String value = transactionAttributes.getProperty(methodName);
			tae.setAsText(value);
			TransactionAttribute attr = (TransactionAttribute) tae.getValue();
			addTransactionalMethod(methodName, attr);
		}
	}

	/**
	 * Add an attribute for a transactional method.
	 * <p>Method names can be exact matches, or of the pattern "xxx*",
	 * "*xxx", or "*xxx*" for matching multiple methods.
	 * @param methodName the name of the method
	 * @param attr attribute associated with the method
	 */
	public void addTransactionalMethod(String methodName, TransactionAttribute attr) {
		if (logger.isDebugEnabled()) {
			logger.debug("Adding transactional method [" + methodName + "] with attribute [" + attr + "]");
		}
		if (this.embeddedValueResolver != null && attr instanceof DefaultTransactionAttribute dta) {
			dta.resolveAttributeStrings(this.embeddedValueResolver);
		}
		this.nameMap.put(methodName, attr);
	}

	@Override
	public void setEmbeddedValueResolver(StringValueResolver resolver) {
		this.embeddedValueResolver = resolver;
	}

	@Override
	public void afterPropertiesSet()  {
		for (TransactionAttribute attr : this.nameMap.values()) {
			if (attr instanceof DefaultTransactionAttribute dta) {
				dta.resolveAttributeStrings(this.embeddedValueResolver);
			}
		}
	}


	/**
	 * 获取目标方法的事务属性，坑能使通过XML的<tx:method/>标签配置的，也可能是通过事务注解比如@Transactional配置的
	 * 如果返回null，那么说明当前方法为非事务方法。
	 *
	 * @param method      调用的方法
	 * @param targetClass 目标类型
	 * @return TransactionAttribute事务属性
	 */
	@Override
	@Nullable
	public TransactionAttribute getTransactionAttribute(Method method, @Nullable Class<?> targetClass) {
		//如果方法不是用户声明的方法或者不是指向用户声明的方法，比如合成方法、GroovyObject方法等，那么直接返回null
		//请注意，尽管是合成的，桥接方法(method.bridge())仍然被视为用户级方法，因为它们最终指向用户声明的泛型方法。
		if (!ClassUtils.isUserLevelMethod(method)) {
			return null;
		}

		//获取方法名
		String methodName = method.getName();
		//尝试寻找直接方法名称的匹配的属性源
		TransactionAttribute attr = this.nameMap.get(methodName);
		/*
		 * 如果为null，那么尝试通配符匹配，并且找到最佳匹配
		 * 最佳匹配规则是：
		 *  如果存在多个匹配的key，那么谁的字符串长度最长，谁就是最佳匹配，就将是使用谁对应的事务属性
		 *  如果长度相等，那么后面匹配的会覆盖此前匹配的……emm
		 */
		if (attr == null) {
			//寻找最匹配的名称匹配。
			String bestNameMatch = null;
			//遍历beanName数组
			for (String mappedName : this.nameMap.keySet()) {
				//如果isMatch返回true，表示匹配当前mappedName
				//并且此前没有匹配其他bestNameMatch，或者此前匹配的bestNameMatch的长度小于等于当前匹配的mappedName的长度
				if (isMatch(methodName, mappedName) &&
						(bestNameMatch == null || bestNameMatch.length() <= mappedName.length())) {
					//获取当前mappedName对应的事务属性
					attr = this.nameMap.get(mappedName);
					//bestNameMatch最佳匹配的beanName设置为当前匹配的mappedName
					bestNameMatch = mappedName;
				}
			}
		}
		//返回最佳匹配的
		return attr;
	}

	/**
	 * Determine if the given method name matches the mapped name.
	 * <p>The default implementation checks for "xxx*", "*xxx", and "*xxx*" matches,
	 * as well as direct equality. Can be overridden in subclasses.
	 * @param methodName the method name of the class
	 * @param mappedName the name in the descriptor
	 * @return {@code true} if the names match
	 * @see org.springframework.util.PatternMatchUtils#simpleMatch(String, String)
	 */
	protected boolean isMatch(String methodName, String mappedName) {
		return PatternMatchUtils.simpleMatch(mappedName, methodName);
	}


	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof NameMatchTransactionAttributeSource otherTas)) {
			return false;
		}
		return ObjectUtils.nullSafeEquals(this.nameMap, otherTas.nameMap);
	}

	@Override
	public int hashCode() {
		return NameMatchTransactionAttributeSource.class.hashCode();
	}

	@Override
	public String toString() {
		return getClass().getName() + ": " + this.nameMap;
	}

}
