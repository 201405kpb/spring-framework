package com.kpb.spring;

import com.kpb.spring.tx.Book;
import com.kpb.spring.tx.BookService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.transaction.interceptor.TransactionAspectSupport;


public class SpringXmlTxDemo {

	public static void main(String[] args) {

		ApplicationContext applicationContext= new ClassPathXmlApplicationContext("classpath:spring-core-tx.xml");
		BookService bookService = (BookService) applicationContext.getBean("bookService");
		Book book = bookService.getBookById(1);
		System.out.println(book);



	}
}
