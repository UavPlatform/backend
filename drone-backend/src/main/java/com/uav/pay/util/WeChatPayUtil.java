package com.uav.pay.util;

import com.uav.pay.config.WeChatPayConfig;
import com.uav.server.exception.PayNotifyException;
import com.github.binarywang.wxpay.bean.notify.SignatureHeader;
import com.github.binarywang.wxpay.bean.notify.WxPayNotifyV3Result;
import com.github.binarywang.wxpay.bean.request.WxPayRefundV3Request;
import com.github.binarywang.wxpay.bean.request.WxPayUnifiedOrderV3Request;
import com.github.binarywang.wxpay.bean.result.WxPayRefundV3Result;
import com.github.binarywang.wxpay.bean.result.WxPayUnifiedOrderV3Result;
import com.github.binarywang.wxpay.bean.result.enums.TradeTypeEnum;
import com.github.binarywang.wxpay.config.WxPayConfig;
import com.github.binarywang.wxpay.exception.WxPayException;
import com.github.binarywang.wxpay.service.WxPayService;
import com.github.binarywang.wxpay.service.impl.WxPayServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class WeChatPayUtil {

    private static volatile WxPayService wxPayService;

    public static Map<String, String> createJsapiOrder(WeChatPayConfig wechatConfig,
                                                       String orderNum, String openid,
                                                       String description, BigDecimal amount) {
        WxPayService service = getWxPayService(wechatConfig);

        WxPayUnifiedOrderV3Request request = new WxPayUnifiedOrderV3Request();
        request.setOutTradeNo(orderNum);
        request.setDescription(description);
        request.setNotifyUrl(wechatConfig.getNotifyUrl());

        WxPayUnifiedOrderV3Request.Amount amountReq = new WxPayUnifiedOrderV3Request.Amount();
        amountReq.setTotal(amount.multiply(BigDecimal.valueOf(100)).intValue());
        amountReq.setCurrency("CNY");
        request.setAmount(amountReq);

        WxPayUnifiedOrderV3Request.Payer payer = new WxPayUnifiedOrderV3Request.Payer();
        payer.setOpenid(openid);
        request.setPayer(payer);

        try {
            WxPayUnifiedOrderV3Result result = service.unifiedOrderV3(TradeTypeEnum.JSAPI, request);
            Map<String, String> resp = new HashMap<>();
            resp.put("prepayId", result.getPrepayId());
            return resp;
        } catch (Exception e) {
            log.error("微信支付下单失败: {}", e.getMessage());
            throw new RuntimeException("微信支付下单失败", e);
        }
    }

    public static WxPayService getWxPayService(WeChatPayConfig config) {
        if (wxPayService == null) {
            synchronized (WeChatPayUtil.class) {
                if (wxPayService == null) {
                    WxPayConfig payConfig = new WxPayConfig();
                    payConfig.setAppId(config.getAppId());
                    payConfig.setMchId(config.getMchId());
                    payConfig.setApiV3Key(config.getApiV3Key());
                    payConfig.setPrivateKeyPath(config.getPrivateKeyPath());
                    payConfig.setPrivateCertPath(config.getPrivateCertPath());

                    WxPayService service = new WxPayServiceImpl();
                    service.setConfig(payConfig);
                    wxPayService = service;
                }
            }
        }
        return wxPayService;
    }

    public static WxPayRefundV3Result refund(WeChatPayConfig wechatConfig,
                                             String transactionId, String outRefundNo,
                                             String reason, BigDecimal totalAmount, BigDecimal refundAmount) {
        WxPayService service = getWxPayService(wechatConfig);

        WxPayRefundV3Request request = new WxPayRefundV3Request();
        request.setTransactionId(transactionId);
        request.setOutRefundNo(outRefundNo);
        request.setReason(reason);

        WxPayRefundV3Request.Amount amount = new WxPayRefundV3Request.Amount();
        amount.setTotal(totalAmount.multiply(BigDecimal.valueOf(100)).intValue());
        amount.setRefund(refundAmount.multiply(BigDecimal.valueOf(100)).intValue());
        amount.setCurrency("CNY");
        request.setAmount(amount);

        try {
            return service.refundV3(request);
        } catch (Exception e) {
            log.error("微信退款失败: {}", e.getMessage());
            throw new RuntimeException("微信退款失败", e);
        }
    }

    public static WxPayNotifyV3Result parseNotifyResult(HttpServletRequest request,
                                                        WeChatPayConfig config) {
        try {
            BufferedReader reader = request.getReader();
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            String body = sb.toString();

            String serial = request.getHeader("Wechatpay-Serial");
            String signature = request.getHeader("Wechatpay-Signature");
            String timestamp = request.getHeader("Wechatpay-Timestamp");
            String nonce = request.getHeader("Wechatpay-Nonce");

            return getWxPayService(config).parseOrderNotifyV3Result(body,
                    new SignatureHeader(serial, signature, timestamp, nonce));
        } catch (Exception e) {
            throw new PayNotifyException("验签失败: " + e.getMessage());
        }
    }
}
