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

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Properties;

/**
 * Basic {@link AutowireCandidateResolver} that performs a full generic type
 * match with the candidate's type if the dependency is declared as a generic type
 * (e.g. Repository&lt;Customer&gt;).
 * <p>基于{@link AutowireCandidateResolver},如果将依赖项声明为泛型类型(例如 Repository &lt;Customer&gt;),
 * 则执行与候选者类型的完全通用类型匹配</p>
 *
 * <p>This is the base class for
 * {@link org.springframework.beans.factory.annotation.QualifierAnnotationAutowireCandidateResolver},
 * providing an implementation all non-annotation-based resolution steps at this level.
 * <p>这是{@link org.springframework.beans.factory.annotation.QualifierAnnotationAutowireCandidateResolver},
 * 的基类，提供此级别上所有基于非注释的解析步骤的实现</p>
 * @author Juergen Hoeller
 * @since 4.0
 */
public class GenericTypeAwareAutowireCandidateResolver extends SimpleAutowireCandidateResolver
		implements BeanFactoryAware, Cloneable {

	@Nullable
	private BeanFactory beanFactory;


	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	@Nullable
	protected final BeanFactory getBeanFactory() {
		return this.beanFactory;
	}


	/**
	 * 确定给定的beanDefinition是否可以自动注入。只对@Autowired注解有效，配置文件中可以通过property显示注入：
	 * <ol>
	 *  <li>如果bdHolder的BeanDefinition对象的不可以自动注入标记结果，直接返回false，表示不可以自动注入</li>
	 *  <li>检查泛型类型匹配【{@link #checkGenericTypeMatch(BeanDefinitionHolder, DependencyDescriptor)}】
	 *  ，并返回匹配结果</li>
	 * </ol>
	 * @param bdHolder beanDefinition,包括bean名和别名封装对象
	 * @param descriptor 目标方法参数或字段的描述符
	 * @return 给定的beanDefinition是否可以自动注入
	 */
	@Override
	public boolean isAutowireCandidate(BeanDefinitionHolder bdHolder, DependencyDescriptor descriptor) {
		//super.isAutowireCandidate实现简单返回bdHolder的BeanDefinition对象的是否可以自动注入标记结果
		// 		【AbstractBeanDefinition.isAutowireCandidate()】
		//优先交由父级判断，如果父级判断结果表明不可以，则不再继续本类处理
		if (!super.isAutowireCandidate(bdHolder, descriptor)) {
			// If explicitly false, do not proceed with any other checks...
			// 如果明确为假，则不要进行任何其他检查
			return false;
		}
		//检查泛型类型匹配
		return checkGenericTypeMatch(bdHolder, descriptor);
	}

	/**
	 * <p>检查泛型类型匹配:
	 *  <ol>
	 *   <li>让descriptor为包装的参数/字段构建一个ResolvableType对象【变量 dependencyType】</li>
	 *   <li>如果dependencyType的JavaType是Class对象实例,就直接返回true，表示可以自动注入，
	 *   因为这个是Class类型的匹配项,没有泛型</li>
	 *   <li>【<b>获取目标类型targetType</b>】
	 *    <ol>
	 *     <li>定义用于引用目标类型的ResolvableType对象，默认为null【变量 targetType】</li>
	 *     <li>定义表示需要缓存类型标记，默认为false 【变量 cacheType】</li>
	 *     <li>定义用于引用bhHolder的RootBeanDefinition对象的RootBeanDefinition对象，默认为null
	 *     【变量 rbd】</li>
	 *     <li>如果bdHolder的BeanDefinition对象是RootBeanDefinition对象实例,让rbd引用
	 *     bhHolder的RootBeanDefinition对象</li>
	 *     <li>如果rbd不为null:
	 *      <ol>
	 *       <li>【<b>尝试获取rbd的缓存属性来得到targetType</b>】让targetType引用rbd的目标类型【{@link RootBeanDefinition#targetType}】</li>
	 *       <li>【<b>尝试通过rbd的配置获取targetType,如获取工厂方法的返回类型</b>】如果targetType为null:
	 *        <ol>
	 *          <li>设置cacheType为true,表示需要缓存类型</li>
	 *          <li>获取rbd工厂方法的返回类型，只有解析出来的返回类型与descriptor包装的依赖类型匹配(不考虑泛型匹配)
	 *          才返回工厂方法的返回类型,将结果赋值给targetType</li>
	 *          <li>如果targetType为null：
	 *           <ol>
	 *            <li>获取rbd所指bean名在beanFactory中的合并后RootBeanDefinition【变量 dbd】</li>
	 *            <li>如果dbd不为null:
	 *             <ol>
	 *              <li>让targetType引用dbd的目标类型</li>
	 *              <li>如果targetType为null,获取dbd工厂方法的返回类型，只有解析出来的返回类型与descriptor包装的
	 *              依赖类型匹配(不考虑泛型匹配)才返回工厂方法的返回类型</li>
	 *             </ol>
	 *            </li>
	 *           </ol>
	 *          </li>
	 *        </ol>
	 *       </li>
	 *      </ol>
	 *     </li>
	 *     <li>如果targetType为null:
	 *      <ol>
	 *       <li>【<b>尝试使用BeanFactory获取targetType</b>】如果beanFactory不为null:
	 *        <ol>
	 *         <li>从beanFactory中获取bhHolder所包装的bean名的bean类型【变量 beanType】</li>
	 *         <li>如果beanType不为null,将beanType封装成ResolvableType对象赋值给targetType</li>
	 *        </ol>
	 *       </li>
	 *       <li>【<b>尝试获取rbd的beanClass属性作为targetType</b>】targetType为null 且 rbd不为null 且 rbd有
	 *       指定了bean类 但 rbd没有工厂方法名
	 *        <ol>
	 *         <li>获取rbd的bean类【{@link RootBeanDefinition#getBeanClass()},变量 beanClass】</li>
	 *         <li>如果bean类不是FactoryBean的实例,将beanType封装成ResolvableType对象赋值给targetType</li>
	 *        </ol>
	 *       </li>
	 *      </ol>
	 *     </li>
	 *    </ol>
	 *   </li>
	 *   <li>【<b>默认情况下，Spring对无法检查的情况默认是给它放行的,可能是出于最大可用性的考虑吧</b>】:
	 *    <ol>
	 *     <li>如果targetType还是为null，默认返回true,让其可以自动注入</li>
	 *     <li>如果需要缓存目标类型,让rbd的目标类型【{@link RootBeanDefinition#targetType}】引用targetType</li>
	 *     <li>如果descriptor允许回退匹配 且 (targetType具有无法解析的泛型 || targetType的Class对象是Properties类对象),
	 *     返回true,让其可以自动注入</li>
	 *    </ol>
	 *   </li>
	 *   <li>【<b>处理能拿到targetType且targetType可进行检查的情况</b>】判断targetType是否属于dependencyType类型【加上了泛型检查】，并将结果返回出去</li>
	 *  </ol>
	 * </p>
	 * Match the given dependency type with its generic type information against the given
	 * candidate bean definition.
	 * <p>将给定的依赖关系类型及其泛型信息与给定的候选beanDefinition进行匹配</p>
	 * @param bdHolder beanDefinition,包括bean名和别名封装对象
	 * @param descriptor 目标方法参数或字段的描述符
	 * @return 给定的beanDefinition是否可以自动注入
	 */
	protected boolean checkGenericTypeMatch(BeanDefinitionHolder bdHolder, DependencyDescriptor descriptor) {
		//让descriptor为包装的参数/字段构建一个ResolvableType对象
		ResolvableType dependencyType = descriptor.getResolvableType();
		//如果dependencyType的JavaType是Class对象实例
		if (dependencyType.getType() instanceof Class) {
			// No generic type -> we know it's a Class type-match, so no need to check again.
			// 没有泛型类型 -> 我们知道这是一个Class类型匹配项,因此无需再次检查
			//返回true，表示可以自动注入，因为这个是Class类型的匹配项,没有泛型
			return true;
		}
		//定义用于引用目标类型的ResolvableType对象，默认为null
		ResolvableType targetType = null;
		//定义表示需要缓存类型标记，默认为false
		boolean cacheType = false;
		//定义用于引用bhHolder的RootBeanDefinition对象的RootBeanDefinition对象，默认为null
		RootBeanDefinition rbd = null;
		//如果bdHolder的BeanDefinition对象是RootBeanDefinition对象实例
		if (bdHolder.getBeanDefinition() instanceof RootBeanDefinition) {
			//让rbd引用bhHolder的RootBeanDefinition对象
			rbd = (RootBeanDefinition) bdHolder.getBeanDefinition();
		}
		//如果rbd不为null
		if (rbd != null) {
			//让targetType引用rbd的目标类型
			targetType = rbd.targetType;
			//如果targetType为null
			if (targetType == null) {
				//设置cacheType为true,表示需要缓存类型
				cacheType = true;
				// First, check factory method return type, if applicable
				// 首先检查工厂方法的返回类型(如果适用)
				// 获取rbd工厂方法的返回类型，只有解析出来的返回类型与descriptor包装的依赖类型匹配(不考虑泛型匹配)
				// 	才返回工厂方法的返回类型:
				targetType = getReturnTypeForFactoryMethod(rbd, descriptor);
				//这里应该是Spring想尽可能的拿到targetType所做的一下保障机制
				//如果targetType为null
				if (targetType == null) {
					//获取rbd所指bean名在beanFactory中的合并后RootBeanDefinition:
					RootBeanDefinition dbd = getResolvedDecoratedDefinition(rbd);
					//如果dbd不为null
					if (dbd != null) {
						//让targetType引用dbd的目标类型
						targetType = dbd.targetType;
						if (targetType == null) {
							//获取dbd工厂方法的返回类型，只有解析出来的返回类型与descriptor包装的依赖类型匹配(不考虑泛型匹配)
							// 	才返回工厂方法的返回类型
							targetType = getReturnTypeForFactoryMethod(dbd, descriptor);
						}
					}
				}
			}
		}
		//如果targetType为null
		if (targetType == null) {
			// Regular case: straight bean instance, with BeanFactory available.
			// 常规情况：纯bean实例，可以使用BeanFactory
			//如果beanFactory不为null
			if (this.beanFactory != null) {
				//从beanFactory中获取bhHolder所包装的bean名的bean类型
				Class<?> beanType = this.beanFactory.getType(bdHolder.getBeanName());
				//如果beanType不为null
				if (beanType != null) {
					//ClassUtils.getUserClass：如果beanType是CGLIB生成的子类，则返回该子类的父类，否则直接返回要检查的类
					//将beanType封装成ResolvableType对象赋值给targetType
					targetType = ResolvableType.forClass(ClassUtils.getUserClass(beanType));
				}
			}
			// Fallback: no BeanFactory set, or no type resolvable through it
			// -> best-effort match against the target class if applicable.
			// 回退：没有BeanFactory设置，或者没有可通过它解析得类型->与目标类的最大努力匹配(如果适用)
			// targetType为null 且 rbd不为null 且 rbd有指定了bean类 但 rbd没有工厂方法名
			if (targetType == null && rbd != null && rbd.hasBeanClass() && rbd.getFactoryMethodName() == null) {
				//获取rbd的bean类
				Class<?> beanClass = rbd.getBeanClass();
				//如果bean类不是FactoryBean的实例
				if (!FactoryBean.class.isAssignableFrom(beanClass)) {
					///ClassUtils.getUserClass：如果beanType是CGLIB生成的子类，则返回该子类的父类，否则直接返回要检查的类
					//将beanType封装成ResolvableType对象赋值给targetType
					targetType = ResolvableType.forClass(ClassUtils.getUserClass(beanClass));
				}
			}
		}
		//在找不到目标类型的情况下，默认返回true,让其可以自动注入
		if (targetType == null) {
			return true;
		}
		//如果需要缓存目标类型
		if (cacheType) {
			//让rbd的目标类型引用targetType
			rbd.targetType = targetType;
		}
		//如果descriptor允许回退匹配 且 (targetType具有无法解析的泛型 || targetType的Class对象是Properties类对象)
		if (descriptor.fallbackMatchAllowed() &&
				(targetType.hasUnresolvableGenerics() || targetType.resolve() == Properties.class)) {
			// Fallback matches allow unresolvable generics, e.g. plain HashMap to Map<String,String>;
			// and pragmatically also java.util.Properties to any Map (since despite formally being a
			// Map<Object,Object>, java.util.Properties is usually perceived as a Map<String,String>).
			//后备匹配允许使用无法解析的泛型,例如从HashMap到Map<String,String>;
			// 并且从实用上讲，任何Map都有java.util.Properties（因为尽管正式是Map<Object,Object>,
			// 所以java.util.Properties通常被视为Map<String,String>).
			return true;
		}
		// Full check for complex generic type match...
		// 全面检查复杂的泛型类型匹配
		// 这里的判断，是针对常规匹配和回退匹配的检查
		// 判断targetType是否属于dependencyType类型【加上了泛型检查】，并将结果返回出去
		return dependencyType.isAssignableFrom(targetType);
	}

	/**
	 * 获取rbd所指bean名在beanFactory中的合并后RootBeanDefinition:
	 * <ol>
	 *  <li>获取rdb的beanDefinition,包括bean名和别名封装对象【变量 decDef】</li>
	 *  <li>如果decDef不为null 且 该工厂是ConfiguableLisableBeanFactory实例:
	 *   <ol>
	 *    <li>将beanFactory强转成ConfigurableLisatableBeanFactory对象【变量 clbf】</li>
	 *    <li>如果clbf包含具有decDef所指bean名的beanDefinition:
	 *     <ol>
	 *      <li>获取defDef所指bean名的合并后BeanDefinition【变量dbd】</li>
	 *      <li>如果dbd是RootBeanDefinition对象,将dbd强转为RootBeanDefinition对象返回出去</li>
	 *     </ol>
	 *    </li>
	 *   </ol>
	 *  </li>
	 *  <li>默认返回null</li>
	 * </ol>
	 * @param rbd bdHolder的RootBeanDefinition对象
	 * @return rbd所指bean名在beanFactory中的合并后RootBeanDefinition；如果没有，就返回null
	 */
	@Nullable
	protected RootBeanDefinition getResolvedDecoratedDefinition(RootBeanDefinition rbd) {
		//获取rdb的beanDefinition,包括bean名和别名封装对象
		BeanDefinitionHolder decDef = rbd.getDecoratedDefinition();
		//如果decDef不为null 且 该工厂是ConfigurableListableBeanFactory实例
		if (decDef != null && this.beanFactory instanceof ConfigurableListableBeanFactory) {
			//将beanFactory强转成ConfigurableListableBeanFactory对象
			ConfigurableListableBeanFactory clbf = (ConfigurableListableBeanFactory) this.beanFactory;
			//如果clbf包含具有decDef所指bean名的beanDefinition
			if (clbf.containsBeanDefinition(decDef.getBeanName())) {
				//获取defDef所指bean名的合并后BeanDefinition
				BeanDefinition dbd = clbf.getMergedBeanDefinition(decDef.getBeanName());
				//如果dbd是RootBeanDefinition对象
				if (dbd instanceof RootBeanDefinition) {
					//将dbd强转为RootBeanDefinition对象返回出去
					return (RootBeanDefinition) dbd;
				}
			}
		}
		//1.deDef为null
		//2.该工厂不是ConfigurableListableBeanFactory实例
		//3.该工厂没有包含具有decDef的bean名的beanDefinition对象，
		//4.得到的beanDefinition对象不是RootBeanDefinition对象
		//默认返回null
		return null;
	}


	/**
	 * 获取工厂方法的返回类型，只有解析出来的返回类型与descriptor包装的依赖类型匹配(不考虑泛型匹配)才返回工厂方法的返回类型:
	 * <ol>
	 *  <li>获取rbd缓存下来的通用类型的工厂方法的返回类型【变量 returnType】</li>
	 *  <li>如果returnType为null:
	 *   <ol>
	 *    <li>获取rbd的解析的工厂方法对象【变量 factoryMethod】</li>
	 *    <li>如果factoryMethod不为null,获取factoryMethod的返回类型的ResolvableType对象并赋值给returnType</li>
	 *   </ol>
	 *  </li>
	 *  <li>如果returnType不为null:
	 *   <ol>
	 *    <li>将returnType解析成Class对象【变量 resolvedClass】</li>
	 *    <li>如果resolvedClass不为null 且 resolvedClass是属于descriptor包装的依赖类型【只是检查Class，并没有对泛型做检查】
	 *    ,就返回returnType</li>
	 *   </ol>
	 *  </li>
	 *  <li>没有解析到返回类型时 或者 解析returnType出来的Class对象不属于descriptor包装的依赖类型时，返回null</li>
	 * </ol>
	 * @param rbd bdHolder的RootBeanDefinition对象
	 * @param descriptor 目标方法参数或字段的描述符
	 * @return 只有解析出来的返回类型与descriptor包装的依赖类型匹配才返回工厂方法的返回类型
	 */
	@Nullable
	protected ResolvableType getReturnTypeForFactoryMethod(RootBeanDefinition rbd, DependencyDescriptor descriptor) {
		// Should typically be set for any kind of factory method, since the BeanFactory
		// pre-resolves them before reaching out to the AutowireCandidateResolver...
		// 通用应为任何一种工厂方法设置，因为BeanFactory在联系AutowireCandidateResolver之前会预先
		// 解析它们
		//获取rbd缓存下来的通用类型的工厂方法的返回类型
		ResolvableType returnType = rbd.factoryMethodReturnType;
		// 如果返回类型为null
		if (returnType == null) {
			//获取rbd的解析的工厂方法对象
			Method factoryMethod = rbd.getResolvedFactoryMethod();
			//如果factoryMethod不为null
			if (factoryMethod != null) {
				//获取factoryMethod的返回类型的ResolvableType对象
				returnType = ResolvableType.forMethodReturnType(factoryMethod);
			}
		}
		//如果返回类型不为null
		if (returnType != null) {
			//将returnType解析成Class对象
			Class<?> resolvedClass = returnType.resolve();
			//如果resolvedClass不为null 且 resolvedClass是属于descriptor的包装的参数/字段的声明的(非泛型)类型【只是检查Class,
			// 并没有对泛型做检查】
			if (resolvedClass != null && descriptor.getDependencyType().isAssignableFrom(resolvedClass)) {
				// Only use factory method metadata if the return type is actually expressive enough
				// for our dependency. Otherwise, the returned instance type may have matched instead
				// in case of a singleton instance having been registered with the container already.
				//仅当返回类型在实际上足以表达我们的依赖性时，才使用工厂元数据。否则，如果已经在容器中注册了单例实例，
				// 则返回的实例类型可能已匹配
				//返回解析后的返回类型
				return returnType;
			}
		}
		//没有解析到返回类型时 或者 解析returnType出来的Class对象不属于descriptor包装的依赖类型，返回null
		return null;
	}


	/**
	 * This implementation clones all instance fields through standard
	 * {@link Cloneable} support, allowing for subsequent reconfiguration
	 * of the cloned instance through a fresh {@link #setBeanFactory} call.
	 * @see #clone()
	 */
	@Override
	public AutowireCandidateResolver cloneIfNecessary() {
		try {
			return (AutowireCandidateResolver) clone();
		}
		catch (CloneNotSupportedException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
