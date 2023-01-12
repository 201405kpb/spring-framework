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

package org.springframework.cache.annotation;

import org.springframework.aot.hint.annotation.Reflective;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;
import java.util.concurrent.Callable;

/**
 * Annotation indicating that the result of invoking a method (or all methods
 * in a class) can be cached.
 * <p>Cacheable 表明开启缓存行为，如果标注类上就表示缓存所有方法,
 * 缓存 key 基于参数生成，也可以指定 SpEL 表达式，或者自定义 KeyGenerator,
 * 如果没有获取到缓存结果，就将方法执行的结果缓存进去，如果方法返回值类型为 Optional，缓存的会是实际值
 *
 * <p>Each time an advised method is invoked, caching behavior will be applied,
 * checking whether the method has been already invoked for the given arguments.
 * A sensible default simply uses the method parameters to compute the key, but
 * a SpEL expression can be provided via the {@link #key} attribute, or a custom
 * {@link org.springframework.cache.interceptor.KeyGenerator} implementation can
 * replace the default one (see {@link #keyGenerator}).
 *
 * <p>If no value is found in the cache for the computed key, the target method
 * will be invoked and the returned value will be stored in the associated cache.
 * Note that {@link java.util.Optional} return types are unwrapped automatically.
 * If an {@code Optional} value is {@linkplain java.util.Optional#isPresent()
 * present}, it will be stored in the associated cache. If an {@code Optional}
 * value is not present, {@code null} will be stored in the associated cache.
 *
 * <p>This annotation may be used as a <em>meta-annotation</em> to create custom
 * <em>composed annotations</em> with attribute overrides.
 *
 * @author Costin Leau
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @author Sam Brannen
 * @see CacheConfig
 * @since 3.1
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Reflective
public @interface Cacheable {

    /**
     * Alias for {@link #cacheNames}.
     * cacheNames 的别名
     */
    @AliasFor("cacheNames")
    String[] value() default {};

    /**
     * Names of the caches in which method invocation results are stored.
     * 用于匹配缓存的目标 cache name
     * <p>Names may be used to determine the target cache (or caches), matching
     * the qualifier value or bean name of a specific bean definition.
     *
     * @see #value
     * @see CacheConfig#cacheNames
     * @since 4.2
     */
    @AliasFor("value")
    String[] cacheNames() default {};

    /**
     * Spring Expression Language (SpEL) expression for computing the key dynamically.
     * <p>Default is {@code ""}, meaning all method parameters are considered as a key,
     * unless a custom {@link #keyGenerator} has been configured.
     * <p>The SpEL expression evaluates against a dedicated context that provides the
     * following meta-data:
     * <ul>
     * <li>{@code #root.method}, {@code #root.target}, and {@code #root.caches} for
     * references to the {@link java.lang.reflect.Method method}, target object, and
     * affected cache(s) respectively.</li>
     * <li>Shortcuts for the method name ({@code #root.methodName}) and target class
     * ({@code #root.targetClass}) are also available.
     * <li>Method arguments can be accessed by index. For instance the second argument
     * can be accessed via {@code #root.args[1]}, {@code #p1} or {@code #a1}. Arguments
     * can also be accessed by name if that information is available.</li>
     * </ul>
     *
     * <p>支持 SpEL 表达式指定 key，默认为空，则基于 keyGenerator 生成，若未指定 keyGenerator，则基于参数生成</p>
     * <p> SpEL 的 context 提供如下参数</p>
     * <ul>
     *     <li>#root.method：目标方法</li>
     *     <li>#root.target：目标对象</li>
     *     <li>#root.caches：目标 Cache</li>
     *     <li>#root.methodName：方法名称简写</li>
     *     <li>#root.targetClass：目标 Class</li>
     *     <li>目标参数的获取有：#root.args[1] || #p1 || #a1</li>
     *
     * </ul>
     */
    String key() default "";

    /**
     * The bean name of the custom {@link org.springframework.cache.interceptor.KeyGenerator} to use.
     * 自定义 KeyGenerator 的 beanName，与 key 属性互斥
     * <p>Mutually exclusive with the {@link #key} attribute.
     *
     * @see CacheConfig#keyGenerator
     */
    String keyGenerator() default "";

    /**
     * The bean name of the custom {@link org.springframework.cache.CacheManager} to use to
     * create a default {@link org.springframework.cache.interceptor.CacheResolver} if none
     * is set already.
     * 如果没有指定 cacheResolver，则基于这个 CacheManager 的 beanName 获取,与 cacheResolver	属性互斥
     * <p>Mutually exclusive with the {@link #cacheResolver}  attribute.
     *
     * @see org.springframework.cache.interceptor.SimpleCacheResolver
     * @see CacheConfig#cacheManager
     */
    String cacheManager() default "";

    /**
     * The bean name of the custom {@link org.springframework.cache.interceptor.CacheResolver} to use.
     * 自定义 CacheResolver 的 beanName
     *
     * @see CacheConfig#cacheResolver
     */
    String cacheResolver() default "";

    /**
     * Spring Expression Language (SpEL) expression used for making the method
     * caching conditional. Cache the result if the condition evaluates to
     * {@code true}.
     * <p>基于 SpEL 的 条件表达式，指定缓存触发的条件，默认为空，即无条件触发
     * <p>Default is {@code ""}, meaning the method result is always cached.
     * <p>The SpEL expression evaluates against a dedicated context that provides the
     * following meta-data:
     * <ul>
     * <li>{@code #root.method}, {@code #root.target}, and {@code #root.caches} for
     * references to the {@link java.lang.reflect.Method method}, target object, and
     * affected cache(s) respectively.</li>
     * <li>Shortcuts for the method name ({@code #root.methodName}) and target class
     * ({@code #root.targetClass}) are also available.
     * <li>Method arguments can be accessed by index. For instance the second argument
     * can be accessed via {@code #root.args[1]}, {@code #p1} or {@code #a1}. Arguments
     * can also be accessed by name if that information is available.</li>
     * </ul>
     */
    String condition() default "";

    /**
     * Spring Expression Language (SpEL) expression used to veto method caching.
     * Veto caching the result if the condition evaluates to {@code true}.
     * <p>对出参进行判断，符合条件的不缓存，不符合的缓存
     * <p>基于 SpEL 对方法执行结果的缓存条件判断
     * <p>默认为空意味着无条件缓存结果
     * <p>在之前 context 的基础上还可以访问
     * <p>#result：方法执行结果，对于Optional 返回值它获取的是实际值
     * <p>Unlike {@link #condition}, this expression is evaluated after the method
     * has been called and can therefore refer to the {@code result}.
     * <p>Default is {@code ""}, meaning that caching is never vetoed.
     * <p>The SpEL expression evaluates against a dedicated context that provides the
     * following meta-data:
     * <ul>
     * <li>{@code #result} for a reference to the result of the method invocation. For
     * supported wrappers such as {@code Optional}, {@code #result} refers to the actual
     * object, not the wrapper</li>
     * <li>{@code #root.method}, {@code #root.target}, and {@code #root.caches} for
     * references to the {@link java.lang.reflect.Method method}, target object, and
     * affected cache(s) respectively.</li>
     * <li>Shortcuts for the method name ({@code #root.methodName}) and target class
     * ({@code #root.targetClass}) are also available.
     * <li>Method arguments can be accessed by index. For instance the second argument
     * can be accessed via {@code #root.args[1]}, {@code #p1} or {@code #a1}. Arguments
     * can also be accessed by name if that information is available.</li>
     * </ul>
     *
     * @since 3.2
     */
    String unless() default "";

    /**
     * Synchronize the invocation of the underlying method if several threads are
     * attempting to load a value for the same key. The synchronization leads to
     * a couple of limitations:
     * 表明目标方法是否在并发环境下支持同步操作，开启时受到如下限制：
     * <ol>
     * <li>{@link #unless()} is not supported 不支持 unless 属性</li>
     * <li>Only one cache may be specified 只能针对一个缓存，由 cacheNames 属性指定</li>
     * <li>No other cache-related operation can be combined 不支持同时有其他操作，或者多个 Cacheable 合并</li>
     * </ol>
     * This is effectively a hint and the actual cache provider that you are
     * using may not support it in a synchronized fashion. Check your provider
     * documentation for more details on the actual semantics.
     * 但究竟是否支持缓存同步其实还是取决于底层供应商的实现
     *
     * @see org.springframework.cache.Cache#get(Object, Callable)
     * @since 4.3
     */
    boolean sync() default false;

}
