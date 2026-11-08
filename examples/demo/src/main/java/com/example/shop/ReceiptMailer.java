package com.example.shop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class ReceiptMailer {

    private static final Logger log = LoggerFactory.getLogger(ReceiptMailer.class);

    /**
     * Runs on another thread, as its own piece of work. Order ids starting with 9 fail, after the
     * request that started it has already succeeded.
     */
    @Async
    public void sendReceipt(String orderId) {
        log.debug("Rendering receipt for order {}", orderId);
        log.debug("Connecting to mail server smtp.example.com");
        if (orderId.startsWith("9")) {
            throw new IllegalStateException("Mail server refused the connection");
        }
        log.info("Receipt sent for order {}", orderId);
    }
}
