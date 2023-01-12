package com.kpb.spring.service;

import com.kpb.spring.tx.Book;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class CacheService {

    @Cacheable("default")
    public Book find(Integer id) {
		if(id==1){
			Book book = new Book();
			book.setId(id);
			book.setName("Spring源码深度解析（第2版）");
			book.setPrice(99.0);
			book.setAuthor("郝佳");
			return book;
		}
        return null;
    }

}
