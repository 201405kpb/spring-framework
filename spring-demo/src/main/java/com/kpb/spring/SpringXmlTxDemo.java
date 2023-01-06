package com.kpb.spring;

import com.kpb.spring.tx.Book;
import com.kpb.spring.tx.BookService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;


public class SpringXmlTxDemo {

	public static void main(String[] args) {
		// 系统环境变量会影响一个系统平台的所有程序，不可在Java程序中设置和修改，JVM环境变量只会影响一个JVM实例，可以在Java程序中设置和修改。我们使用System.setProperty(key,value)修改和设置JVM环境变量。
		System.setProperty("x","config");
		ApplicationContext applicationContext= new ClassPathXmlApplicationContext("classpath:spring-core-tx.xml");
		BookService bookService = (BookService) applicationContext.getBean("bookService");
		Book book = bookService.getBookById(1);
		System.out.println(book);



	}
}
