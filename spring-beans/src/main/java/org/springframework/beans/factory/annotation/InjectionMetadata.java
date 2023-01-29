/*
 * Copyright 2002-2023 the original author or authors.
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

import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;

/**
 * Internal class for managing injection metadata.
 *
 * <p>Not intended for direct use in applications.
 *
 * <p>Used by {@link AutowiredAnnotationBeanPostProcessor},
 * {@link org.springframework.context.annotation.CommonAnnotationBeanPostProcessor}, and
 * {@link org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor}.
 *
 * @author Juergen Hoeller
 * @since 2.5
 */
public class InjectionMetadata {

	/**
	 * An empty {@code InjectionMetadata} instance with no-op callbacks.
	 * @since 5.2
	 */
	public static final InjectionMetadata EMPTY = new InjectionMetadata(Object.class, Collections.emptyList()) {
		@Override
		protected boolean needsRefresh(Class<?> clazz) {
			return false;
		}
		@Override
		public void checkConfigMembers(RootBeanDefinition beanDefinition) {
		}
		@Override
		public void inject(Object target, @Nullable String beanName, @Nullable PropertyValues pvs) {
		}
		@Override
		public void clear(@Nullable PropertyValues pvs) {
		}
	};


	private final Class<?> targetClass;

	private final Collection<InjectedElement> injectedElements;

	@Nullable
	private volatile Set<InjectedElement> checkedElements;


	/**
	 * Create a new {@code InjectionMetadata instance}.
	 * <p>Preferably use {@link #forElements} for reusing the {@link #EMPTY}
	 * instance in case of no elements.
	 * @param targetClass the target class
	 * @param elements the associated elements to inject
	 * @see #forElements
	 */
	public InjectionMetadata(Class<?> targetClass, Collection<InjectedElement> elements) {
		this.targetClass = targetClass;
		this.injectedElements = elements;
	}


	/**
	 * Return the {@link InjectedElement elements} to inject.
	 * @return the elements to inject
	 */
	public Collection<InjectedElement> getInjectedElements() {
		return Collections.unmodifiableCollection(this.injectedElements);
	}

	/**
	 * Determine whether this metadata instance needs to be refreshed.
	 * @param clazz the current target class
	 * @return {@code true} indicating a refresh, {@code false} otherwise
	 * @since 5.2.4
	 */
	protected boolean needsRefresh(Class<?> clazz) {
		return this.targetClass != clazz;
	}

	public void checkConfigMembers(RootBeanDefinition beanDefinition) {
		if (this.injectedElements.isEmpty()) {
			this.checkedElements = Collections.emptySet();
		}
		else {
			// 1.遍历检查所有要注入的元素
			Set<InjectedElement> checkedElements = new LinkedHashSet<>((this.injectedElements.size() * 4 / 3) + 1);
			for (InjectedElement element : this.injectedElements) {
				// 2.如果beanDefinition的externallyManagedConfigMembers属性不包含该member
				Member member = element.getMember();
				if (!beanDefinition.isExternallyManagedConfigMember(member)) {
					// 3.将该member添加到beanDefinition的externallyManagedConfigMembers属性
					beanDefinition.registerExternallyManagedConfigMember(member);
					// 4.并将element添加到checkedElements
					checkedElements.add(element);
				}
			}
			// 5.赋值给checkedElements（检查过的元素）
			this.checkedElements = checkedElements;
		}
	}

	public void inject(Object target, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
		Collection<InjectedElement> checkedElements = this.checkedElements;
		//要注入的字段集合
		Collection<InjectedElement> elementsToIterate =
				(checkedElements != null ? checkedElements : this.injectedElements);
		if (!elementsToIterate.isEmpty()) {
			for (InjectedElement element : elementsToIterate) {
				//遍历每个字段 注入
				element.inject(target, beanName, pvs);
			}
		}
	}

	/**
	 * Clear property skipping for the contained elements.
	 * @since 3.2.13
	 */
	public void clear(@Nullable PropertyValues pvs) {
		Collection<InjectedElement> checkedElements = this.checkedElements;
		Collection<InjectedElement> elementsToIterate =
				(checkedElements != null ? checkedElements : this.injectedElements);
		if (!elementsToIterate.isEmpty()) {
			for (InjectedElement element : elementsToIterate) {
				element.clearPropertySkipping(pvs);
			}
		}
	}


	/**
	 * Return an {@code InjectionMetadata} instance, possibly for empty elements.
	 * @param elements the elements to inject (possibly empty)
	 * @param clazz the target class
	 * @return a new {@link #InjectionMetadata(Class, Collection)} instance
	 * @since 5.2
	 */
	public static InjectionMetadata forElements(Collection<InjectedElement> elements, Class<?> clazz) {
		return (elements.isEmpty() ? new InjectionMetadata(clazz, Collections.emptyList()) :
				new InjectionMetadata(clazz, elements));
	}

	/**
	 * Check whether the given injection metadata needs to be refreshed.
	 * @param metadata the existing metadata instance
	 * @param clazz the current target class
	 * @return {@code true} indicating a refresh, {@code false} otherwise
	 * @see #needsRefresh(Class)
	 */
	public static boolean needsRefresh(@Nullable InjectionMetadata metadata, Class<?> clazz) {
		return (metadata == null || metadata.needsRefresh(clazz));
	}


	/**
	 * A single injected element.
	 */
	public abstract static class InjectedElement {

		protected final Member member;

		protected final boolean isField;

		@Nullable
		protected final PropertyDescriptor pd;

		@Nullable
		protected volatile Boolean skip;

		protected InjectedElement(Member member, @Nullable PropertyDescriptor pd) {
			this.member = member;
			this.isField = (member instanceof Field);
			this.pd = pd;
		}

		public final Member getMember() {
			return this.member;
		}

		protected final Class<?> getResourceType() {
			if (this.isField) {
				return ((Field) this.member).getType();
			}
			else if (this.pd != null) {
				return this.pd.getPropertyType();
			}
			else {
				return ((Method) this.member).getParameterTypes()[0];
			}
		}

		protected final void checkResourceType(Class<?> resourceType) {
			if (this.isField) {
				Class<?> fieldType = ((Field) this.member).getType();
				if (!(resourceType.isAssignableFrom(fieldType) || fieldType.isAssignableFrom(resourceType))) {
					throw new IllegalStateException("Specified field type [" + fieldType +
							"] is incompatible with resource type [" + resourceType.getName() + "]");
				}
			}
			else {
				Class<?> paramType =
						(this.pd != null ? this.pd.getPropertyType() : ((Method) this.member).getParameterTypes()[0]);
				if (!(resourceType.isAssignableFrom(paramType) || paramType.isAssignableFrom(resourceType))) {
					throw new IllegalStateException("Specified parameter type [" + paramType +
							"] is incompatible with resource type [" + resourceType.getName() + "]");
				}
			}
		}

		/**
		 * Either this or {@link #getResourceToInject} needs to be overridden.
		 * 完成每一个注入点的注入操作，包括@WebServiceRef、@EJB、@Resource注解的解析
		 */
		protected void inject(Object target, @Nullable String requestingBeanName, @Nullable PropertyValues pvs)
				throws Throwable {
			/*
			 * 如果是字段属性注入点
			 */
			if (this.isField) {
				//获取字段
				Field field = (Field) this.member;
				//设置字段的可访问属性，即field.setAccessible(true)
				ReflectionUtils.makeAccessible(field);
				/*
				 * 1 反射注入该字段属性的值，getResourceToInject方法默认返回null，
				 * 该方法被WebServiceRefElement、EjbRefElement、ResourceElement
				 * 这几个子类重写，用于获取要注入的属性值，我们主要看ResourceElement重写的方法
				 */
				field.set(target, getResourceToInject(target, requestingBeanName));
			}
			/*否则，表示一个方法注入点*/
			else {
				/*
				 * 2 检查是否可以跳过该"属性"注入，也就是看此前找到的pvs中是否存在该名字的属性，如果存在就跳过，不存在就不跳过
				 * 这里可以发现：
				 *  setter方法的注入流程的优先级为：XML手动设置的property > XML设置自动注入的找到的property -> 注解设置的property
				 *  前面的流程中没找到指定名称的property时，当前流程才会查找property
				 */
				if (checkPropertySkipping(pvs)) {
					return;
				}
				try {
					//获取方法
					Method method = (Method) this.member;
					//设置方法的可访问属性，即field.setAccessible(true)
					ReflectionUtils.makeAccessible(method);
					/*
					 * 3 反射调用该方法，设置参数的值，getResourceToInject方法默认返回null，
					 * 该方法被WebServiceRefElement、EjbRefElement、ResourceElement
					 * 这几个子类重写，用于获取要注入的属性值，我们主要看ResourceElement重写的方法
					 */
					method.invoke(target, getResourceToInject(target, requestingBeanName));
				} catch (InvocationTargetException ex) {
					throw ex.getTargetException();
				}
			}
		}

		/**
		 * Check whether this injector's property needs to be skipped due to
		 * an explicit property value having been specified. Also marks the
		 * affected property as processed for other processors to ignore it.
		 * 检查由于指定了显式属性值，是否需要跳过注入此属性。还将受影响的属性标记为已处理，供其他处理器忽略它。
		 * @param pvs 此前通过<property/>标签以及byName或者byType找到的注入项值，防止重复注入
		 */
		protected boolean checkPropertySkipping(@Nullable PropertyValues pvs) {
			//skip属性
			Boolean skip = this.skip;
			//如果skip不为null，那么返回该值
			if (skip != null) {
				return skip;
			}
			//如果pvs为null，那么设置为false，并返回false，表示不跳过
			if (pvs == null) {
				this.skip = false;
				return false;
			}
			//加同步
			synchronized (pvs) {
				//如果skip不为null，那么返回该值
				skip = this.skip;
				if (skip != null) {
					return skip;
				}
				//如果pd不为null，在创建注入点对象时，对于字段来说为null，对于setter方法来说不为null
				//因为只有setter方法会出现重复注入，因为此前找到的pvs的注入项都是setter方法注入
				if (this.pd != null) {
					//如果包含name，表示此前已经找到了这个setter方法
					if (pvs.contains(this.pd.getName())) {
						//设置skip为true，返回true，可跳过
						this.skip = true;
						return true;
					}
					//否则如果是MutablePropertyValues
					else if (pvs instanceof MutablePropertyValues mpvs) {
						//标记为已处理
						mpvs.registerProcessedProperty(this.pd.getName());
					}
				}
				//设置为false，返回false，不可跳过
				this.skip = false;
				return false;
			}
		}

		/**
		 * Clear property skipping for this element.
		 * @since 3.2.13
		 */
		protected void clearPropertySkipping(@Nullable PropertyValues pvs) {
			if (pvs == null) {
				return;
			}
			synchronized (pvs) {
				if (Boolean.FALSE.equals(this.skip) && this.pd != null && pvs instanceof MutablePropertyValues mpvs) {
					mpvs.clearProcessedProperty(this.pd.getName());
				}
			}
		}

		/**
		 * Either this or {@link #inject} needs to be overridden.
		 */
		@Nullable
		protected Object getResourceToInject(Object target, @Nullable String requestingBeanName) {
			return null;
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof InjectedElement otherElement)) {
				return false;
			}
			return this.member.equals(otherElement.member);
		}

		@Override
		public int hashCode() {
			return this.member.getClass().hashCode() * 29 + this.member.getName().hashCode();
		}

		@Override
		public String toString() {
			return getClass().getSimpleName() + " for " + this.member;
		}
	}

}
