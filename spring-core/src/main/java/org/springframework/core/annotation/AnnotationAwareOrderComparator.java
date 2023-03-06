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

package org.springframework.core.annotation;

import org.springframework.core.DecoratingProxy;
import org.springframework.core.OrderComparator;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.lang.Nullable;

import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;
import java.util.List;

/**
 * {@code AnnotationAwareOrderComparator} is an extension of
 * {@link OrderComparator} that supports Spring's
 * {@link org.springframework.core.Ordered} interface as well as the
 * {@link Order @Order} and {@link jakarta.annotation.Priority @Priority}
 * annotations, with an order value provided by an {@code Ordered}
 * instance overriding a statically defined annotation value (if any).
 * <p>AnnotationAwareOrderComparator是OrderComparator的扩展,它支持Spring
 * 的org.springframework.core.Ordered接口以及@Oreder和@Priority注解,其中
 * Ordered实例提供的Order值将覆盖静态定义的注解值(如果有)</p>
 * <p>Consult the Javadoc for {@link OrderComparator} for details on the
 * sort semantics for non-ordered objects.
 * <p>有关OrderComparator的信息,请查阅Javadoc，以获取有关排序对象的排序语义
 * 的详细信息。</p>
 *
 * @author Juergen Hoeller
 * @author Oliver Gierke
 * @author Stephane Nicoll
 * @since 2.0.1
 * @see org.springframework.core.Ordered
 * @see org.springframework.core.annotation.Order
 * @see jakarta.annotation.Priority
 */
public class AnnotationAwareOrderComparator extends OrderComparator {

	/**
	 * Shared default instance of {@code AnnotationAwareOrderComparator}.
	 * <p>AnnotationAwareOrderComparator的共享默认实例</p>
	 */
	public static final AnnotationAwareOrderComparator INSTANCE = new AnnotationAwareOrderComparator();

	/**
	 * <p>获取obj的优先级值:
	 *  <ol>
	 *   <li>优先使用父级findOrder获取obj的优先级值【变量 order】</li>
	 *   <li>如果获取成功，直接返回出去，不再进行其他操作</li>
	 *   <li>取出obj的所有注解，从注解中获取@Order或@javax.annotation.Priority的优先级值</li>
	 *  </ol>
	 * </p>
	 * This implementation checks for {@link Order @Order} or
	 * {@link jakarta.annotation.Priority @Priority} on various kinds of
	 * elements, in addition to the {@link org.springframework.core.Ordered}
	 * check in the superclass.
	 * <p>除了超类中的rg.springframework.core.Ordered检查之外，此实现还检查各种
	 * 元素的@Order或@Priority</p>
	 * @param obj 检查对象
	 * @return 优先级值;如果找不到,则为null
	 * @see #findOrderFromAnnotation(Object)
	 */
	@Override
	@Nullable
	protected Integer findOrder(Object obj) {
		//优先使用父级findOrder获取obj的优先级值
		Integer order = super.findOrder(obj);
		//如果获取成功，直接返回出去，不再进行其他操作
		if (order != null) {
			return order;
		}
		//取出obj的所有注解，从注解中获取优先级值
		return findOrderFromAnnotation(obj);
	}


	/**
	 * 取出obj的所有注解，从注解中获取@Order或@javax.annotation.Priority的优先级值
	 * <ol>
	 *  <li>如果obj是AnnotatedElement对象，就引用该obj，否则获取obj的Class对象作为
	 *  AnnotatedElemet对象【变量 element】</li>
	 *  <li>创建一个新的MegedAnnotations实例，该实例包含element的所有注解(对整个类型
	 *  层次结构进行完整搜索，包括超类和已实现的接口)和元注解【变量 annotations】</li>
	 *  <li>从annotations中获取@Order或@javax.annotation.Priority的优先级值【变量 order】</li>
	 *  <li>如果优先级值为null且obj是装饰代理类,获取obj的被代理的对象来递归进行
	 *  再一次的获取优先级值，然后将结果返回出去</li>
	 *  <li>/返回优先级值【order】</li>
	 * </ol>
	 * @param obj 要检查的对象
	 * @return 优先级值;如果找不到,则为null
	 */
	@Nullable
	private Integer findOrderFromAnnotation(Object obj) {
		/*
		 * AnnotatedElement：代表了在当前JVM中的一个“被注解元素”（可以是Class，Method，Field，Constructor，Package等）。
		 * 在Java语言中，所有实现了这个接口的“元素”都是可以“被注解的元素”。使用这个接口中声明的方
		 * 法可以读取（通过Java的反射机制）“被注解元素”的注解。这个接口中的所有方法返回的注解都是
		 * 不可变的、并且都是可序列化的。这个接口中所有方法返回的数组可以被调用者修改，而不会影响其返回给其他调用者的数组。
		 * 参考博客：https://www.jianshu.com/p/953e26463fbc
		 */
		//如果obj是AnnotatedElement对象，就引用该obj，否则获取obj的Class对象作为AnnotatedElement对象，因为Class对象默认继承AnnotatedElement
		AnnotatedElement element = (obj instanceof AnnotatedElement ae ? ae : obj.getClass());
		MergedAnnotations annotations = MergedAnnotations.from(element, SearchStrategy.TYPE_HIERARCHY);
		//从annotations中获取@Order或@javax.annotation.Priority的优先级值
		Integer order = OrderUtils.getOrderFromAnnotations(element, annotations);
		//如果优先级值为null且obj是装饰代理类
		if (order == null && obj instanceof DecoratingProxy) {
			//获取obj的被代理的对象来递归进行再一次的获取优先级值，然后将结果返回出去
			return findOrderFromAnnotation(((DecoratingProxy) obj).getDecoratedClass());
		}
		//返回优先级值
		return order;
	}

	/**
	 * This implementation retrieves an @{@link jakarta.annotation.Priority}
	 * value, allowing for additional semantics over the regular @{@link Order}
	 * annotation: typically, selecting one object over another in case of
	 * multiple matches but only one object to be returned.
	 *
	 * <p>此实现检索一个{@link jakarta.annotation.Priority}值,从而允许在常规@Order注解
	 * 上使用其他语义:通常，在多个匹配项的情况下，选择一个对象而不是另一个对象，但仅
	 * 返回一个对象</p>
	 */
	@Override
	@Nullable
	public Integer getPriority(Object obj) {
		//如果obj是Class对象
		if (obj instanceof Class) {
			//获取obj上声明的Priority注解的值并返回出去
			return OrderUtils.getPriority((Class<?>) obj);
		}
		//获取obj的Class对象上声明的Priority注解的值并返回出去
		Integer priority = OrderUtils.getPriority(obj.getClass());
		///如果Priority注解的值为null且obj是装饰代理类
		if (priority == null  && obj instanceof DecoratingProxy) {
			//获取obj的被代理的对象来递归进行再一次的获取Priority注解的值，然后将结果返回出去
			return getPriority(((DecoratingProxy) obj).getDecoratedClass());
		}
		//返回Priority注解的值
		return priority;
	}


	/**
	 * Sort the given list with a default {@link AnnotationAwareOrderComparator}.
	 * <p>使用默认的AnnotationAwareOrderComparator</p>
	 * <p>Optimized to skip sorting for lists with size 0 or 1,
	 * in order to avoid unnecessary array extraction.
	 * <p>优化后可跳过大小为0或1的列表的排序,以避免不必要的数组提取</p>
	 * @param list the List to sort -- 列表排序
	 * @see java.util.List#sort(java.util.Comparator)
	 */
	public static void sort(List<?> list) {
		//如果list至少有一个元素
		if (list.size() > 1) {
			//使用默认的AnnotationAwareOrderComparator进行排序
			list.sort(INSTANCE);
		}
	}

	/**
	 * Sort the given array with a default AnnotationAwareOrderComparator.
	 * <p>使用默认的AnnotationAwareOrderComparator</p>
	 * <p>Optimized to skip sorting for lists with size 0 or 1,
	 * in order to avoid unnecessary array extraction.
	 * <p>优化后可跳过大小为0或1的列表的排序,以避免不必要的数组提取</p>
	 * @param array the array to sort -- 数组排序
	 * @see java.util.Arrays#sort(Object[], java.util.Comparator)
	 */
	public static void sort(Object[] array) {
		//如果array至少有一个元素
		if (array.length > 1) {
			//使用默认的AnnotationAwareOrderComparator进行排序
			Arrays.sort(array, INSTANCE);
		}
	}

	/**
	 * Sort the given array or List with a default AnnotationAwareOrderComparator,
	 * if necessary. Simply skips sorting when given any other value.
	 * <p>如果有必要，使用默认的AnnotationAwareOrderComparator对给定的数组或列表进行排序。
	 * 给定其他任何值时，只需跳过排序</p>
	 * <p>Optimized to skip sorting for lists with size 0 or 1,
	 * in order to avoid unnecessary array extraction.
	 * <p>优化后可跳过大小为0或1的列表排序,以避免不必要的数组提取</p>
	 * @param value the array or List to sort -- 数组或要排序的列表
	 * @see java.util.Arrays#sort(Object[], java.util.Comparator)
	 */
	public static void sortIfNecessary(Object value) {
		//如果value是对象数组
		if (value instanceof Object[]) {
			//使用默认的OrderComparator对value数组进行排序
			sort((Object[]) value);
		}
		//如果value是List对象
		else if (value instanceof List) {
			//使用默认的OrderComparator对value List进行排序
			sort((List<?>) value);
		}
	}

}
