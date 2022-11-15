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

import java.io.Serializable;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.lang.Nullable;
import org.springframework.transaction.interceptor.AbstractFallbackTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * Implementation of the
 * {@link org.springframework.transaction.interceptor.TransactionAttributeSource}
 * interface for working with transaction metadata in JDK 1.5+ annotation format.
 *
 * <p>This class reads Spring's JDK 1.5+ {@link Transactional} annotation and
 * exposes corresponding transaction attributes to Spring's transaction infrastructure.
 * Also supports JTA 1.2's {@link jakarta.transaction.Transactional} and EJB3's
 * {@link jakarta.ejb.TransactionAttribute} annotation (if present).
 * This class may also serve as base class for a custom TransactionAttributeSource,
 * or get customized through {@link TransactionAnnotationParser} strategies.
 *
 * @author Colin Sampaleanu
 * @author Juergen Hoeller
 * @since 1.2
 * @see Transactional
 * @see TransactionAnnotationParser
 * @see SpringTransactionAnnotationParser
 * @see Ejb3TransactionAnnotationParser
 * @see org.springframework.transaction.interceptor.TransactionInterceptor#setTransactionAttributeSource
 * @see org.springframework.transaction.interceptor.TransactionProxyFactoryBean#setTransactionAttributeSource
 */
@SuppressWarnings("serial")
public class AnnotationTransactionAttributeSource extends AbstractFallbackTransactionAttributeSource
		implements Serializable {

	private static final boolean jta12Present;

	private static final boolean ejb3Present;

	static {
		ClassLoader classLoader = AnnotationTransactionAttributeSource.class.getClassLoader();
		jta12Present = ClassUtils.isPresent("jakarta.transaction.Transactional", classLoader);
		ejb3Present = ClassUtils.isPresent("jakarta.ejb.TransactionAttribute", classLoader);
	}

	private final boolean publicMethodsOnly;

	private final Set<TransactionAnnotationParser> annotationParsers;


	/**
	 * Create a default AnnotationTransactionAttributeSource, supporting
	 * public methods that carry the {@code Transactional} annotation
	 * or the EJB3 {@link jakarta.ejb.TransactionAttribute} annotation.
	 */
	public AnnotationTransactionAttributeSource() {
		this(true);
	}

	/**
	 * Create a custom AnnotationTransactionAttributeSource, supporting
	 * public methods that carry the {@code Transactional} annotation
	 * or the EJB3 {@link jakarta.ejb.TransactionAttribute} annotation.
	 * @param publicMethodsOnly whether to support public methods that carry
	 * the {@code Transactional} annotation only (typically for use
	 * with proxy-based AOP), or protected/private methods as well
	 * (typically used with AspectJ class weaving)
	 */
	public AnnotationTransactionAttributeSource(boolean publicMethodsOnly) {
		//设置属性
		this.publicMethodsOnly = publicMethodsOnly;
		//如果存在JTA 1.2的javax.transaction.Transactional事务注解或者EJB3的javax.ejb.TransactionAttribute事务注解
		if (jta12Present || ejb3Present) {
			//设置事务注解解析器集合
			this.annotationParsers = new LinkedHashSet<>(4);
			//添加SpringTransactionAnnotationParser解析器
			//用于解析Spring自带的org.springframework.transaction.annotation.Transactional注解
			this.annotationParsers.add(new SpringTransactionAnnotationParser());
			//添加对应的外部事物注解的解析器
			if (jta12Present) {
				this.annotationParsers.add(new JtaTransactionAnnotationParser());
			}
			if (ejb3Present) {
				this.annotationParsers.add(new Ejb3TransactionAnnotationParser());
			}
		} else {
			//如果没有其他的外部事务注解
			//设置事务注解解析器集合，其中仅有一个SpringTransactionAnnotationParser解析器
			//用于解析Spring自带的org.springframework.transaction.annotation.Transactional注解
			this.annotationParsers = Collections.singleton(new SpringTransactionAnnotationParser());
		}
	}

	/**
	 * Create a custom AnnotationTransactionAttributeSource.
	 * @param annotationParser the TransactionAnnotationParser to use
	 */
	public AnnotationTransactionAttributeSource(TransactionAnnotationParser annotationParser) {
		this.publicMethodsOnly = true;
		Assert.notNull(annotationParser, "TransactionAnnotationParser must not be null");
		this.annotationParsers = Collections.singleton(annotationParser);
	}

	/**
	 * Create a custom AnnotationTransactionAttributeSource.
	 * @param annotationParsers the TransactionAnnotationParsers to use
	 */
	public AnnotationTransactionAttributeSource(TransactionAnnotationParser... annotationParsers) {
		this.publicMethodsOnly = true;
		Assert.notEmpty(annotationParsers, "At least one TransactionAnnotationParser needs to be specified");
		this.annotationParsers = new LinkedHashSet<>(Arrays.asList(annotationParsers));
	}

	/**
	 * Create a custom AnnotationTransactionAttributeSource.
	 * @param annotationParsers the TransactionAnnotationParsers to use
	 */
	public AnnotationTransactionAttributeSource(Set<TransactionAnnotationParser> annotationParsers) {
		this.publicMethodsOnly = true;
		Assert.notEmpty(annotationParsers, "At least one TransactionAnnotationParser needs to be specified");
		this.annotationParsers = annotationParsers;
	}


	/**
	 * @param targetClass 目标类型
	 * @return true 表示属于候选class，false 表示不会继续进行方法匹配
	 */
	@Override
	public boolean isCandidateClass(Class<?> targetClass) {
		//遍历注解解析器集合
		for (TransactionAnnotationParser parser : this.annotationParsers) {
			//判断是否可以解析目标类型
			if (parser.isCandidateClass(targetClass)) {
				//只要有一个解析器能够解析，那么就算合格
				return true;
			}
		}
		return false;
	}

	@Override
	@Nullable
	protected TransactionAttribute findTransactionAttribute(Class<?> clazz) {
		return determineTransactionAttribute(clazz);
	}

	@Override
	@Nullable
	protected TransactionAttribute findTransactionAttribute(Method method) {
		return determineTransactionAttribute(method);
	}

	/**
	 * Determine the transaction attribute for the given method or class.
	 * 确定给定方法或类的事务属性。
	 * <p>This implementation delegates to configured
	 * {@link TransactionAnnotationParser TransactionAnnotationParsers}
	 * for parsing known annotations into Spring's metadata attribute class.
	 * Returns {@code null} if it's not transactional.
	 * 该实现委托配置的TransactionAnnotationParser将已知的注解解析到Spring的元数据属性类中。如果不是事务性的，则返回null。
	 * <p>Can be overridden to support custom annotations that carry transaction metadata.
	 *  可以覆盖该方法，以支持携带事务元数据的自定义事务注解。
	 * @param element the annotated method or class
	 * @return the configured transaction attribute, or {@code null} if none was found
	 */
	@Nullable
	protected TransactionAttribute determineTransactionAttribute(AnnotatedElement element) {
		//和此前判断class一样，又是按顺序遍历设置的注解解析器集合，委托内部的解析器解析
		for (TransactionAnnotationParser parser : this.annotationParsers) {
			//这次是调用解析器的parseTransactionAnnotation方法
			TransactionAttribute attr = parser.parseTransactionAnnotation(element);
			//找到一个就返回，不会应用后续的解析器
			//什么意思呢，如果一个方法使用了多种事务注解，仍然会按照解析器的顺序解析，最终只有一个注解会生效
			//默认头部解析器就是SpringTransactionAnnotationParser，支持Spring的@Transactional注解
			if (attr != null) {
				return attr;
			}
		}
		return null;
	}

	/**
	 * By default, only public methods can be made transactional.
	 */
	@Override
	protected boolean allowPublicMethodsOnly() {
		return this.publicMethodsOnly;
	}


	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof AnnotationTransactionAttributeSource otherTas)) {
			return false;
		}
		return (this.annotationParsers.equals(otherTas.annotationParsers) &&
				this.publicMethodsOnly == otherTas.publicMethodsOnly);
	}

	@Override
	public int hashCode() {
		return this.annotationParsers.hashCode();
	}

}
