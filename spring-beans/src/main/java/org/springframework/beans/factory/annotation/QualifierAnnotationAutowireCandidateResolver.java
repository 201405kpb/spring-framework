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

package org.springframework.beans.factory.annotation;

import org.springframework.beans.SimpleTypeConverter;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.AutowireCandidateResolver;
import org.springframework.beans.factory.support.GenericTypeAwareAutowireCandidateResolver;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@link AutowireCandidateResolver} implementation that matches bean definition qualifiers
 * against {@link Qualifier qualifier annotations} on the field or parameter to be autowired.
 * Also supports suggested expression values through a {@link Value value} annotation.
 * <p>AutowireCandidateResolver实现,将bean定义限定符与要自动连接的字段或参数上的限定符相匹配。还
 * 通过值注释支持建议的表达式值</p>
 *
 * <p>Also supports JSR-330's {@link jakarta.inject.Qualifier} annotation, if available.
 * <p>如果可用，还支持JSR-330的{@link jakarta.inject.Qualifier}注解</p>
 *
 * @author Mark Fisher
 * @author Juergen Hoeller
 * @author Stephane Nicoll
 * @since 2.5
 * @see AutowireCandidateQualifier
 * @see Qualifier
 * @see Value
 */
public class QualifierAnnotationAutowireCandidateResolver extends GenericTypeAwareAutowireCandidateResolver {

	/**
	 * Spring支持的Qualifier注解缓存
	 */
	private final Set<Class<? extends Annotation>> qualifierTypes = new LinkedHashSet<>(2);

	/**
	 * Value注解类型，默认使用{@link Value}
	 */
	private Class<? extends Annotation> valueAnnotationType = Value.class;


	/**
	 * Create a new QualifierAnnotationAutowireCandidateResolver
	 * for Spring's standard {@link Qualifier} annotation.
	 * <p>为Spring的标准Qualifier注解创建一个新的QualifierAnnotationAutowireCandidateResovler.</p>
	 * <p>Also supports JSR-330's {@link jakarta.inject.Qualifier} annotation, if available.
	 * <p>如果可用，还支持JSR-330的javax.inject.Qualifier批注</p>
	 */
	@SuppressWarnings("unchecked")
	public QualifierAnnotationAutowireCandidateResolver() {
		//添加Qualifier注解到qualifierTypes中
		this.qualifierTypes.add(Qualifier.class);
		try {
			//如果引入了JSR-330库，会将javax.inject.Qualifier也提交到qualifierType中
			this.qualifierTypes.add((Class<? extends Annotation>) ClassUtils.forName("jakarta.inject.Qualifier",
					QualifierAnnotationAutowireCandidateResolver.class.getClassLoader()));
		}
		catch (ClassNotFoundException ex) {
			// JSR-330 API not available - simply skip.
			// JSR-330 API 不可用-只需跳过
		}
	}

	/**
	 * Create a new QualifierAnnotationAutowireCandidateResolver
	 * for the given qualifier annotation type.
	 * <p>为给定的限定符注解类型一个新的QualifierAnnotationAutowireCandidateResolver</p>
	 * @param qualifierType the qualifier annotation to look for -- 要查找的限定符注解
	 */
	public QualifierAnnotationAutowireCandidateResolver(Class<? extends Annotation> qualifierType) {
		//如果qualifierType为null，抛出异常
		Assert.notNull(qualifierType, "'qualifierType' must not be null");
		//添加qualifierType注解到qualifierTypes中
		this.qualifierTypes.add(qualifierType);
		//注意：这里只是添加qualifierType,但javax.inject.Qualifier,
		// 		org.springframework.beans.factory.annotation.Qualifier是没有添加到qualifierTypes中的，
		// 也就是说，通过该构造函数创建出来的对象只支持qualifierType。
	}

	/**
	 * Create a new QualifierAnnotationAutowireCandidateResolver
	 * for the given qualifier annotation types.
	 * <p>为给定的限定符注解类型创建一个新的QualifierAnnotationAutowireCandidateResolver</p>
	 * @param qualifierTypes the qualifier annotations to look for -- 要查找的限定符的注解
	 */
	public QualifierAnnotationAutowireCandidateResolver(Set<Class<? extends Annotation>> qualifierTypes) {
		//如果qualifierType为null，抛出异常
		Assert.notNull(qualifierTypes, "'qualifierTypes' must not be null");
		//添加qualifierTypes注解到qualifierTypes中
		this.qualifierTypes.addAll(qualifierTypes);
		//注意：这里只是添加qualifierTypes,但javax.inject.Qualifier,
		// 		org.springframework.beans.factory.annotation.Qualifier是没有添加到qualifierTypes中的，
		// 也就是说，通过该构造函数创建出来的对象只支持qualifierTypes。
	}


	/**
	 * Register the given type to be used as a qualifier when autowiring.
	 * <p>注册给定类型以在自动装配时用作限定符</p>
	 * <p>This identifies qualifier annotations for direct use (on fields,
	 * method parameters and constructor parameters) as well as meta
	 * annotations that in turn identify actual qualifier annotations.
	 * <p>这标识了直接使用的限定符注解(在字段,方法参数和构造函数参数上)以及元标记,
	 * 后者又标识了实际的限定符注解</p>
	 * <p>This implementation only supports annotations as qualifier types.
	 * The default is Spring's {@link Qualifier} annotation which serves
	 * as a qualifier for direct use and also as a meta annotation.
	 * <p>此实现仅支持将注解作为限定符类型。默认的是Spring的Qualifier注解,它可以直接
	 * 用作为限定符,也可以作为meta注解</p>
	 * @param qualifierType the annotation type to register -- 要注册的注解类型
	 */
	public void addQualifierType(Class<? extends Annotation> qualifierType) {
		//添加qualifierType注解到qualifierTypes中
		this.qualifierTypes.add(qualifierType);
	}

	/**
	 * Set the 'value' annotation type, to be used on fields, method parameters
	 * and constructor parameters.
	 * <p>设置"value"注解类型，以用于字段，方法参数和构造函数参数</p>
	 * <p>The default value annotation type is the Spring-provided
	 * {@link Value} annotation.
	 * <p>默认值注解类型是Spring提供的Value注解</p>
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation type to indicate a default value
	 * expression for a specific argument.
	 * <p>存在此setter属性,以便开发人员可以提供自己的(非特定于Spring的)注解类型,
	 * 以指示特定参数的默认值表达式</p>
	 */
	public void setValueAnnotationType(Class<? extends Annotation> valueAnnotationType) {
		this.valueAnnotationType = valueAnnotationType;
	}


	/**
	 * <p>确定bbHolder是否可以自动装配(beanDefinition是否允许依赖注入,泛型类型是否匹配,限定符注解是否匹配):
	 *  <ol>
	 *   <li>引用父级匹配结果(beanDefinition是否允许依赖注入,泛型类型是否匹配)【变量 match】</li>
	 *   <li>限定符注解匹配:
	 *    <ol>
	 *     <li>如果匹配【match】:
	 *      <ol>
	 *       <li>让match引用descriptor所包装的field/methodParamter对象的注解与bdHolder的beanDefinition匹配结果</li>
	 *       <li>如果匹配【match】,尝试再检查方法上的注解:
	 *        <ol>
	 *         <li>获取desciptor包装的方法参数对象【变量 methodParamter】</li>
	 *         <li>如果是method是构造函数或者method是无返回值方法:让match引用descriptor所包装的methodParam所属
	 *         的method的注解与bdHolder的beanDefinition匹配结果</li>
	 *        </ol>
	 *       </li>
	 *      </ol>
	 *     </li>
	 *    </ol>
	 *   </li>
	 *   <li>返回匹配结果【match】</li>
	 *  </ol>
	 * </p>
	 * Determine whether the provided bean definition is an autowire candidate.
	 * <p>确定所提供的beanDefinition是否为自动装配候选</p>
	 * <p>To be considered a candidate the bean's <em>autowire-candidate</em>
	 * attribute must not have been set to 'false'. Also, if an annotation on
	 * the field or parameter to be autowired is recognized by this bean factory
	 * as a <em>qualifier</em>, the bean must 'match' against the annotation as
	 * well as any attributes it may contain. The bean definition must contain
	 * the same qualifier or match by meta attributes. A "value" attribute will
	 * fallback to match against the bean name or an alias if a qualifier or
	 * attribute does not match.
	 * <p>要被视为候选对象,bean的autowire-candidate属性必须未设置为'false'.另外,
	 * 如果此beanFactory将要自动连接的字段或参数上的注解识别为限定符,则bean必须与该注解
	 * 及其可能包含的任何属性'匹配'.beanDefinition必须包含相同的限定符或按meta属性进行
	 * 匹配。如果限定符或属性不匹配,则'value'属性将回退与Bean名称或别名匹配</p>
	 * @param bdHolder – beanDefinition,包括bean名和别名封装对象
	 * @param descriptor – 目标方法参数或字段的描述符
	 * @see Qualifier
	 */
	@Override
	public boolean isAutowireCandidate(BeanDefinitionHolder bdHolder, DependencyDescriptor descriptor) {
		//确定bdHolder的beanDefinition是否可以自动注入(beanDefinition是否允许依赖注入,泛型类型是否匹配,限定符注解/限定符信息是否匹配)
		boolean match = super.isAutowireCandidate(bdHolder, descriptor);
		//如果匹配
		if (match) {
			//让match引用descriptor所包装的field对象/methodParameter的注解与bdHolder的beanDefinition匹配结果
			match = checkQualifiers(bdHolder, descriptor.getAnnotations());
			//如果匹配，尝试再检查方法上的注解，因为限定符注解可能没有设置在field/methodParamaters上，而是加在方法上，所以检查一下方法
			if (match) {
				//获取desciptor包装的方法参数对象
				MethodParameter methodParam = descriptor.getMethodParameter();
				//如果有方法参数
				if (methodParam != null) {
					//获取方法参数所属的方法对象
					Method method = methodParam.getMethod();
					//method == null表示构造函数 void.class表示方法返回void
					//如果是method是构造函数或者method是无返回值方法
					if (method == null || void.class == method.getReturnType()) {
						//methodParam.getMethodAnnotations():返回methodParam所属的method的注解,即加在方法上的注解
						//让match引用descriptor所包装的methodParam所属的method的注解与bdHolder的beanDefinition匹配结果
						match = checkQualifiers(bdHolder, methodParam.getMethodAnnotations());
					}
				}
			}
		}
		//返回匹配结果
		return match;
	}

	/**
	 * <p>将descriptor的注解与bdHolder的beanDefinition匹配:
	 *  <ol>
	 *   <li>如果annotationsToSearche没有注解,返回true,表示匹配</li>
	 *   <li>创建一个类型转换器实例，用于对注解/BeanDefinition的属性值的转换【变量 typeConverter】</li>
	 *   <li>遍历annotationsToSearch,元素annotation:
	 *	  <ol>
	 *	   <li>【<b>先直接用annotation进行匹配</b>】:
	 *	    <ol>
	 *	     <li>获取annotation的的Class对象【变量 type】</li>
	 *	     <li>定义检查元注解标记，默认为true【变量 checkMeta】</li>
	 *	     <li>定义需要回退匹配元注解标记，默认为false</li>
	 *	     <li>如果anntationType是限定符注解:
	 *	      <ol>
	 *	       <li>如果annotation与bdHolder的beanDefinition不匹配时(匹配限定符),需要回退匹配元
	 *	       注解标记【fallbackToMeta】设置为true，表示需要回退匹配元注解标记</li>
	 *	       <li>否则检查元注解【checkMeta】设置为false，表示不需要检查元注解</li>
	 *	      </ol>
	 *	     </li>
	 *	    </ol>
	 *	   </li>
	 *	   <li>【<b>用annotation里注解进行匹配</b>】:
	 *	    <ol>
	 *	     <li>如果需要检查元信息【checkMeta】:
	 *	      <ol>
	 *	       <li>定义一个找到限定符注解标记【变量 foundMeta 】，默认为false,表示未找到限定符注解</li>
	 *	       <li>遍历注解里的所有注解,元素为metaAnn:
	 *	        <ol>
	 *	         <li>获取metaAnn的Class对象【变量 metaType】</li>
	 *	         <li>如果anntationType是限定符注解:
	 *	          <ol>
	 *	           <li>设置找到元信息标记设置为true，表示找到了限定符注解</li>
	 *	           <li>如果(需要回退且metaAnn的value属性是空字符) 或者 metaAnn与bdHolder的
	 *	            beanDefinition不匹配(匹配限定符),返回false，表示不匹配</li>
	 *	          </ol>
	 *	         </li>
	 *	        </ol>
	 *	       </li>
	 *	       <li>如果需要回退检查元注解但没有找到限定符注解,返回false，表示不匹配</li>
	 *	      </ol>
	 *	     </li>
	 *	    </ol>
	 *	  </li>
	 *	 </ol>
	 *	</li>
	 *  <li>默认返回true，表示匹配</li>
	 * </ol>
	 * </p>
	 * Match the given qualifier annotations against the candidate bean definition.
	 * <p>将给定的限定符注解与候选beanDefinition匹配</p>
	 * @param bdHolder – beanDefinition,包括bean名和别名封装对象
	 * @param annotationsToSearch – descriptor所包装的属性/方法参数的注解
	 */
	protected boolean checkQualifiers(BeanDefinitionHolder bdHolder, Annotation[] annotationsToSearch) {
		//如果annotationsToSearche没有注解
		if (ObjectUtils.isEmpty(annotationsToSearch)) {
			//返回true，默认让其通过
			return true;
		}
		//创建一个类型转换器实例，用于对注解/BeanDefinition的属性值的转换
		SimpleTypeConverter typeConverter = new SimpleTypeConverter();
		//遍历annotationsToSearch
		for (Annotation annotation : annotationsToSearch) {
			//先直接用annotation进行匹配
			//获取annotation的的Class对象
			Class<? extends Annotation> type = annotation.annotationType();
			//定义检查元注解标记，默认为true
			boolean checkMeta = true;
			//定义需要回退匹配元注解标记，默认为false
			boolean fallbackToMeta = false;
			//如果anntationType是限定符注解
			if (isQualifier(type)) {
				//如果annotation与bdHolder的beanDefinition不匹配时(匹配限定符)
				if (!checkQualifier(bdHolder, annotation, typeConverter)) {
					//需要回退匹配元注解标记设置为true，表示需要回退匹配元注解标记
					fallbackToMeta = true;
				}
				else {
					//检查元注解设置为false，表示不需要检查元注解
					checkMeta = false;
				}
			}
			//因为annotation有可能注解里配置限定符注解，所以可以检查一下注解里注解，需要注意的是该下面只检查一级嵌套，
			//超过一级嵌套的层级都会忽略
			//如果需要检查元信息
			if (checkMeta) {
				//找到限定符注解标记，默认为false,表示未找到限定符注解
				boolean foundMeta = false;
				//遍历注解里的所有注解
				for (Annotation metaAnn : type.getAnnotations()) {
					//获取metaAnn的Class对象
					Class<? extends Annotation> metaType = metaAnn.annotationType();
					//如果anntationType是限定符注解
					if (isQualifier(metaType)) {
						//找到元信息标记设置为true，表示找到了限定符注解
						foundMeta = true;
						// Only accept fallback match if @Qualifier annotation has a value...
						// Otherwise it is just a marker for a custom qualifier annotation.
						//仅当@Qualifier注解具有值时才接收回退匹配...
						//否则是自定义限定符注解标记。
						//如果(需要回退且metaAnn的value属性是空字符) 或者 metaAnn与bdHolder的beanDefinition不匹配(匹配限定符)
						if ((fallbackToMeta && ObjectUtils.isEmpty(AnnotationUtils.getValue(metaAnn))) ||
								!checkQualifier(bdHolder, metaAnn, typeConverter)) {
							//返回false，表示不匹配
							return false;
						}
					}
				}
				//如果需要回退检查元注解但没有找到限定符注解
				if (fallbackToMeta && !foundMeta) {
					//返回false，表示不匹配
					return false;
				}
			}
		}
		//默认返回true，表示匹配
		return true;
	}

	/**
	 * <p>anntationType是否是限定符注解:
	 *  <ol>
	 *   <li>遍历qualifierTypes,元素为qualifierType:
	 *    <ol>
	 *     <li>如果annotationType与qualifierType相同 且 annotationType里有配置qualifierType注解,就返回true，
	 *     表示是可识别的限定符类型</li>
	 *    </ol>
	 *   </li>
	 *   <li>默认返回false，表示不是可识别的限定符类型</li>
	 *  </ol>
	 * </p>
	 * Checks whether the given annotation type is a recognized qualifier type.
	 * <p>检查给定的注解类型是否可识别的限定符类型</p>
	 */
	protected boolean isQualifier(Class<? extends Annotation> annotationType) {
		//遍历qualifierTypes
		for (Class<? extends Annotation> qualifierType : this.qualifierTypes) {
			//如果annotationType与qualifierType相同 且 annotationType里有配置qualifierType注解
			if (annotationType.equals(qualifierType) || annotationType.isAnnotationPresent(qualifierType)) {
				//返回true，表示是可识别的限定符类型
				return true;
			}
		}
		//默认返回false，表示不是可识别的限定符类型
		return false;
	}

	/**
	 * <p>将annotation与bdHolder的beanDefinition匹配(匹配限定符):
	 *  <ol>
	 *   <li>获取annotation的注解类型【变量 type】</li>
	 *   <li>获取bdHolder的RootBeanDefinition对象【变量 bd】</li>
	 *   <li>获取type在bd中限定符，即标签qualifier【变量 qualifier】</li>
	 *   <li>如果qualifier为null,尝试用type的短类名获取</li>
	 *   <li>【如果没有qualifier为null，表示<b>没有配置标签qualifier,则优先通过@Quanlifier进行匹配</b>】:
	 *    <ol>
	 *     <li>如果qualifier为null:
	 *      <ol>
	 *       <li>从bd的bean注解信息中获取type的注释对象【变量 targetAnnotation,用于存储type的注解对象】</li>
	 *       <li>如果targetAnnotation为null,尝试从bd的工厂方法中获取targetAnnotation</li>
	 *       <li>如果targetAnnotation为null,尝试从bd的合并后的RootBeanDefintion的工厂方法中获取targetAnnotation</li>
	 *       <li>如果targetAnnotation为null,尝试从bean工厂中获取targetAnnotation</li>
	 *       <li>如果targetAnnotation为null且bd已缓存了beanClass,尝试从bd的beanClass中获取targetAnnotation</li>
	 *       <li>如果targetAnnotation不为null且targetAnnotation与annotation相等(会对注解里的属性也进行检查),
	 *       返回true,表示可以自动注入</li>
	 *      </ol>
	 *     </li>
	 *    </ol>
	 *   </li>
	 *   <li>获取annotation的所有属性映射【变量 attributes】</li>
	 *   <li>如果没有属性且没有qualifier,则返回false，表示不可自动注入;因为引用对象这时是有加上注解来限定的，
	 *   即使该注解没有属性，对应的bd也要有相应的qualifier标签约束才算匹配</li>
	 *   <li>【<b>匹配所有两者的所有限定符属性</b>】:
	 *    <ol>
	 *     <li>遍历attributes:
	 *      <ol>
	 *       <li>获取属性名 【变量 attributeName】</li>
	 *       <li>获取属性值,即引用对限定注解上所自定义的信息,可以理解为期望属性值 【变量 expectedValue】</li>
	 *       <li>定义一个用于存储实际值的Object,即候选RootBeanDefinition对象的属性值【变量 actualValue】</li>
	 *       <li>如果quanlifier不为null,actualValue尝试引用quanlifier的attributeNamed的属性值</li>
	 *       <li>如果actualValue为null,actualValue尝试引用db的attributeName的属性值</li>
	 *       <li>【单独处理属性名为value的情况】如果actualValue为null且属性名是'value'且期望值是String类型且期望值与bean名称或者
	 *       expectedValue是bdHolder中存储的别名，就可以检查下一个属性【continue】</li>
	 *       <li>如果actualValue为null且quanlifier不为null,actualValue尝试引用annotation的attributeName的默认值</li>
	 *       <li>如果actualValue不为null,对actualValue进行类型转换，以对应上expectedValue类型</li>
	 *       <li>如果actualValue与expecteValue不相等，返回false，表示不可以自动注入</li>
	 *      </ol>
	 *     </li>
	 *    </ol>
	 *   </li>
	 *   <li>经过两者的所有属性的匹配都一致后，返回true，表示可以自动注入</li>
	 *  </ol>
	 * </p>
	 * Match the given qualifier annotation against the candidate bean definition.
	 * <p>将给定的限定符注解与候选beanDefinition匹配</p>
	 * @param bdHolder – beanDefinition,包括bean名和别名封装对象
	 * @param annotation descriptor所包装的属性/方法参数的注解
	 * @param typeConverter 类型转换器
	 * @return 是否可以自动注入
	 */
	protected boolean checkQualifier(
			BeanDefinitionHolder bdHolder, Annotation annotation, TypeConverter typeConverter) {
		//获取annotation的注解类型
		Class<? extends Annotation> type = annotation.annotationType();
		//获取bdHolder的RootBeanDefinition对象
		RootBeanDefinition bd = (RootBeanDefinition) bdHolder.getBeanDefinition();
		//获取type在bd中限定符，即标签qualifier
		AutowireCandidateQualifier qualifier = bd.getQualifier(type.getName());
		//如果qualifier为null
		if (qualifier == null) {
			//ClassUtils.getShortName(type)：获取type的短类名，即去掉了包名，且对内部类名会将$替换成.
			//获取type短类名在bd中限定符
			qualifier = bd.getQualifier(ClassUtils.getShortName(type));
		}
		//如果没有qualifier为null，表示没有配置标签qualifier,则优先通过@Quanlifier进行匹配
		//如果qualifier为null
		if (qualifier == null) {
			// First, check annotation on qualified element, if any
			// 首先，检查合格元素上的注解(如果有)
			//从bd的bean注解信息中获取type的注释对象
			Annotation targetAnnotation = getQualifiedElementAnnotation(bd, type);
			// Then, check annotation on factory method, if applicable
			// 然后，检查工厂方法的注解(如果可用)
			// 尝试从bd的工厂方法中获取targetAnnotation
			//如果targetAnnotation为null
			if (targetAnnotation == null) {
				//从bd的工厂方法中获取type的注解对象
				targetAnnotation = getFactoryMethodAnnotation(bd, type);
			}
			// 尝试从bd的合并后的RootBeanDefintion的工厂方法中获取targetAnnotation
			//如果targetAnnotation为null
			if (targetAnnotation == null) {
				//获取rbd所指bean名在beanFactory中的合并后RootBeanDefinition
				RootBeanDefinition dbd = getResolvedDecoratedDefinition(bd);
				//如果dbd不为null
				if (dbd != null) {
					//从dbd的工厂方法中获取type的注解对象
					targetAnnotation = getFactoryMethodAnnotation(dbd, type);
				}
			}
			//如果targetAnnotation为null
			if (targetAnnotation == null) {
				// Look for matching annotation on the target class
				//在目标类上训阵匹配注释
				// 尝试从bean工厂中获取targetAnnotation
				//如果bean工厂不为null
				if (getBeanFactory() != null) {
					try {
						//获取bdHolder指定的beanName在bean工厂中的Class对象
						Class<?> beanType = getBeanFactory().getType(bdHolder.getBeanName());
						//如果beanType不为null
						if (beanType != null) {
							//ClassUtils.getUserClass(beanType):如果beanType是CGLIB生成的子类，则返回该子类的父类，否则直接返回要检查的类
							//从beanType中获取type的注解对象
							targetAnnotation = AnnotationUtils.getAnnotation(ClassUtils.getUserClass(beanType), type);
						}
					}
					//捕捉 没有此类BeanDefinition异常
					catch (NoSuchBeanDefinitionException ex) {
						// Not the usual case - simply forget about the type check...
						//并非通常情况，只需忘记类型检查即可...
					}
				}
				//尝试从bd的beanClass中获取targetAnnotation
				//如果targetAnnotation为null且bd已缓存了beanClass
				if (targetAnnotation == null && bd.hasBeanClass()) {
					//从bd已缓存beanClass的中获取type的注解对象
					targetAnnotation = AnnotationUtils.getAnnotation(ClassUtils.getUserClass(bd.getBeanClass()), type);
				}
			}
			/**
			 * Annotation.equals:如果指定的对象表示在逻辑上等效于该注解的注解,则返回true。
			 * 换句话说，如果指定对象是与此实例具有相同注解类型的实例，并且其所有成员都与此
			 * 注解的对应的成员相同，则返回true，如下所示:
			 * 1.如果x==y,则两个分别为x和y的原始类型成员被视为相等,除非其类型为float或double.
			 * 2.如果Float.valueOf(x).equals(Float.valueOf(y)),则两个分别为x和y的相应float
			 * 成员被视为相等。(与==运算符不同,NaN被视为等于其自身,并且0.0f不等于-0.0f).
			 * 3.如果Double.valueOf(x).equals(Double.valueOf(y)),则两个分别为x和y的双精度成员
			 * 被视为相等.(与==运算符不同,NaN被视为等于其自身,并且0.0不等于-0.0)
			 * 4.如果x.equals(y),则将两个相应的String,Class,enumn或注释类型的成员(其值分辨为x和
			 * y)视为相等.(请注意,此定义对于注解类型的成员是递归的
			 * 5.如果Arrays.equals(x,y),则两个对应的数组类型成员x和y被视为相等，以适当重载
			 * java.util.Arrays.equals
			 */
			//如果targetAnnotation不为null且targetAnnotation与annotation相等(会对注解里的属性也进行检查)
			if (targetAnnotation != null && targetAnnotation.equals(annotation)) {
				return true;
			}
		}
		//获取annotation的所有属性映射
		Map<String, Object> attributes = AnnotationUtils.getAnnotationAttributes(annotation);
		//如果没有属性且没有限定符
		if (attributes.isEmpty() && qualifier == null) {
			// If no attributes, the qualifier must be present
			// 如果没有属性，则必须存在限定符
			//返回false，表示不可自动注入;因为引用对象这时是有加上注解来限定的，即使该注解没有属性，对应的bd也要有相应的qualifier标签
			// 约束才算匹配
			return false;
		}
		//匹配所有两者的所有限定符属性，Spring认为只有两者的限定信息属性都一致的情况下，才是可以自动注入
		//遍历属性映射
		for (Map.Entry<String, Object> entry : attributes.entrySet()) {
			//获取属性名
			String attributeName = entry.getKey();
			//获取属性值
			Object expectedValue = entry.getValue();
			//定义一个用于存储实际值的Object
			Object actualValue = null;
			// Check qualifier first
			// 首先检查qualifier
			//首先从db的限定符中对应的属性
			//如果quanlifier不为null
			if (qualifier != null) {
				//引用quanlifier的attributeNamed的属性值
				actualValue = qualifier.getAttribute(attributeName);
			}
			//尝试获取bd对应的属性
			//如果actualValue为null
			if (actualValue == null) {
				// Fall back on bean definition attribute
				// 回退beanDefinition属性值
				//引用db的attributeName的属性值
				actualValue = bd.getAttribute(attributeName);
			}
			//这里表示处理属性名为value的情况，Spring默认value属性只要是String类，都可以认为它用于匹配别名
			//如果actualValue为null且属性名是'value'且期望值是String类型且期望值与bean名称或者
			// expectedValue是bdHolder中存储的别名
			if (actualValue == null && attributeName.equals(AutowireCandidateQualifier.VALUE_KEY) &&
					expectedValue instanceof String && bdHolder.matchesName((String) expectedValue)) {
				// Fall back on bean name (or alias) match
				// 回退bean名称(或别名匹配)
				// 既然与别名匹配，就可以检查下一个属性了，以后下面的操作不是针对value属性的。
				//检查下一个属性
				continue;
			}
			//处理在没有任何自定义设置该属性名的值的情况，可能是因为属性名有默认值。
			//如果actualValue为null且quanlifier不为null
			if (actualValue == null && qualifier != null) {
				// Fall back on default, but only if the qualifier is present
				// 回退到默认值，但前提是存在限定符
				actualValue = AnnotationUtils.getDefaultValue(annotation, attributeName);
			}
			//对actualValue进行类型转换，以对应上expectedValue类型
			//如果actualValue不为null
			if (actualValue != null) {
				actualValue = typeConverter.convertIfNecessary(actualValue, expectedValue.getClass());
			}
			//可能有会觉得这里actualValue会空指针，但是其实不会的，因为注解的属性在没有设置默认值的情况下，在使用的使用必须要
			// 对该属性进行赋值，否则编译失败
			//判断actualValue与expecteValue是否相等
			//如果actualValue与expecteValue不相等，返回false，表示不可以自动注入
			if (!expectedValue.equals(actualValue)) {
				return false;
			}
		}
		//经过两者的所有属性的匹配都一致后，返回true，表示可以自动注入
		return true;
	}

	/**
	 * 从bd的QualifiedElement中获取type的注释对象
	 * @param bd bdHolder的RootBeanDefinition
	 * @param type 属于限定符的注解
	 * @return type的注释对象
	 */
	@Nullable
	protected Annotation getQualifiedElementAnnotation(RootBeanDefinition bd, Class<? extends Annotation> type) {
		//AnnotatedElement代表了在当前JVM中的一个“被注解元素”（可以是Class，Method，Field，Constructor，Package等）。
		//RootBeanDefinition.getQualifiedElement:Bean的注解信息
		//从bd中获取QualifiedElemnet
		AnnotatedElement qualifiedElement = bd.getQualifiedElement();
		//从qualifiedElement中获取type的注释对象,仅支持单级的元注解获取
		return (qualifiedElement != null ? AnnotationUtils.getAnnotation(qualifiedElement, type) : null);
	}

	/**
	 * 从bd的工厂方法中获取type的注解对象
	 * @param bd bdHolder的RootBeanDefinition
	 * @param type 属于限定符的注解
	 * @return type的注释对象
	 */
	@Nullable
	protected Annotation getFactoryMethodAnnotation(RootBeanDefinition bd, Class<? extends Annotation> type) {
		//从bd中获取解析后的工厂方法作为java方法对象
		Method resolvedFactoryMethod = bd.getResolvedFactoryMethod();
		//从resolvedFactoryMethod中获取type的注释对象,仅支持单级的元注解获取
		return (resolvedFactoryMethod != null ? AnnotationUtils.getAnnotation(resolvedFactoryMethod, type) : null);
	}


	/**
	 * <p>确定decriptor是否需要依赖项(检查descriptor是否设置了需要依赖项,descriptor的@Autowired注解信息):
	 *  <ol>
	 *   <li>如果父级确定descriptor不需要依赖项(descriptor是否设置了需要依赖项),返回false，表示不需要此依赖</li>
	 *   <li>获取decriptor的Autowired注解对象【变量 autowired】</li>
	 *   <li>如果没有配置@Autowired或者Autowired对象表明需要依赖项目，就返回true,表示需要依赖项;
	 *   否则返回false，表示不需要依赖项</li>
	 *  </ol>
	 * </p>
	 * Determine whether the given dependency declares an autowired annotation,
	 * checking its required flag.
	 * <p>确定给定的依赖项是否声明了自动装配的注解,并且检查其必需标志</p>
	 * @see Autowired#required()
	 */
	@Override
	public boolean isRequired(DependencyDescriptor descriptor) {
		//如果父级确定descriptor不需要依赖项(descriptor是否设置了需要依赖项)。
		if (!super.isRequired(descriptor)) {
			//返回false，表示不需要此依赖
			return false;
		}
		//获取decriptor的Autowired注解对象
		Autowired autowired = descriptor.getAnnotation(Autowired.class);
		//如果没有配置@Autowired或者Autowired对象表明需要依赖项目，就返回true,表示需要依赖项；
		// 否则返回false，表示不需要依赖项
		return (autowired == null || autowired.required());
	}

	/**
	 * Determine whether the given dependency declares a qualifier annotation.
	 * <p>确定给定的依赖项是否声明了限定符注解</p>
	 * @see #isQualifier(Class)
	 * @see Qualifier
	 */
	@Override
	public boolean hasQualifier(DependencyDescriptor descriptor) {
		//遍历decriptor的所有注解
		for (Annotation ann : descriptor.getAnnotations()) {
			//逐个判断ann的Class对象是否与qualifierTypes的某个元素相等
			if (isQualifier(ann.annotationType())) {
				//若相等，表示声明了限定符注解
				return true;
			}
		}
		//经过遍历，没发现限定符注解时，返回false表示没有声明限定符注解
		return false;
	}

	/**
	 * <p>获取descriptor的@Value的value属性值:
	 *  <ol>
	 *   <li>从descriptor所包装的field/MethodParameter所有注解中获取@Value注解的value属性值【变量 value】</li>
	 *   <li>如果value为null:
	 *    <ol>
	 *     <li>获取decriptor的方法参数对象 【变量 methodParam】</li>
	 *     <li>如果有方法参数对象,从descriptor所包装的methodParameter的所属Method的所有注解中获取@Value注解的value属性值</li>
	 *    </ol>
	 *   </li>
	 *   <li>返回@Value的属性值</li>
	 *  </ol>
	 * </p>
	 * Determine whether the given dependency declares a value annotation.
	 * <p>确定给定的依赖项是否声明了@Value注解</p>
	 * @see Value
	 */
	@Override
	@Nullable
	public Object getSuggestedValue(DependencyDescriptor descriptor) {
		//从descriptor所包装的field/MethodParameter所有注解中获取@Value注解的value属性值
		Object value = findValue(descriptor.getAnnotations());
		//如果value为null
		if (value == null) {
			//获取decriptor的方法参数对象
			MethodParameter methodParam = descriptor.getMethodParameter();
			//如果有方法参数对象
			if (methodParam != null) {
				//从descriptor所包装的methodParameter的所属Method的所有注解中获取@Value注解的value属性值
				value = findValue(methodParam.getMethodAnnotations());
			}
		}
		//返回@Value的属性值
		return value;
	}

	/**
	 * <p>从annotationsToSearch中获取@Value注解的value属性值:
	 *  <ol>
	 *   <li>如果有注解:
	 *    <ol>
	 *     <li>通过annotationsToSearch得到合并后的注解属性，然后从合并后的注解属性中获取@Value注解的属性【变量 attr】</li>
	 *     <li>如果注解属性不为null,从attr中获取value属性的属性值并返回出去</li>
	 *    </ol>
	 *   </li>
	 *   <li>如果没有注解，或者没有配置@Value注解，默认返回null</li>
	 *  </ol>
	 * </p>
	 * Determine a suggested value from any of the given candidate annotations.
	 * <p>从任何给定的候选注解中确定建议值</p>
	 * @param annotationsToSearch desciptor的field/methodParamater的所有注解
	 */
	@Nullable
	protected Object findValue(Annotation[] annotationsToSearch) {
		//如果有注解
		if (annotationsToSearch.length > 0) {   // qualifier annotations have to be local //限定符注释必须是本地的
			//从合并后的注解属性中获取@Value注解的属性
			AnnotationAttributes attr = AnnotatedElementUtils.getMergedAnnotationAttributes(
					AnnotatedElementUtils.forAnnotations(annotationsToSearch), this.valueAnnotationType);
			//如果注解属性不为null
			if (attr != null) {
				//从attr中获取value属性的属性值并返回出去
				return extractValue(attr);
			}
		}
		//如果没有注解，或者没有配置@Value注解，默认返回null
		return null;
	}

	/**
	 * <p>从attr中获取value属性的属性值:
	 *  <ol>
	 *   <li>从注解属性中获取value的属性值【变量 value】</li>
	 *   <li>如果value没有值,抛出非法状态异常:值注解必须居于属性值</li>
	 *   <li>返回value的属性值</li>
	 *  </ol>
	 * </p>
	 * Extract the value attribute from the given annotation.
	 * <p>从给定的注解中提取value属性</p>
	 * @since 4.3
	 */
	protected Object extractValue(AnnotationAttributes attr) {
		//从注解属性中获取value的属性值
		Object value = attr.get(AnnotationUtils.VALUE);
		//如果value没有值
		if (value == null) {
			//抛出非法状态异常:值注解必须居于属性值
			throw new IllegalStateException("Value annotation must have a value attribute");
		}
		//返回value的属性值
		return value;
	}

}