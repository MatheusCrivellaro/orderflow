package com.matheus.orderflow;

import com.matheus.orderflow.product.Product;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

public final class TestFixtures {

    private TestFixtures() {
    }

    public static Product product(Long id, String name, String price, int stock) {
        Product product = new Product(name, "descrição", new BigDecimal(price), stock);
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }
}
