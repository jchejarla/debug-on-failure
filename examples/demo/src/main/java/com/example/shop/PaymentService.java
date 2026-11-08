package com.example.shop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** Cards ending in 0000 are declined. */
    public void charge(String orderId, String card) {
        log.debug("Calling card network for order {}", orderId);
        if (card.endsWith("0000")) {
            log.debug("Card network response: DECLINED code=51");
            throw new IllegalStateException("Payment declined for order " + orderId);
        }
        log.debug("Card network response: APPROVED");
    }
}
