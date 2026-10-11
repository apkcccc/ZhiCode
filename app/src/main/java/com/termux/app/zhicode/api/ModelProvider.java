package com.termux.app.zhicode.api;

import com.termux.app.zhicode.model.AssistantTurn;
import com.termux.app.zhicode.model.SessionConfig;

import org.json.JSONArray;

/**
 * 一次模型请求的传输实现。
 *
 * <p>实现是三种协议各一份（见 {@link ModelProviders}）。接口只有两个方法，
 * 因为「怎么把报文拼出来、怎么把流解回来」是实现内部的事：
 * 调用方只关心「发出去、拿回一条助手回复」。
 *
 * <p>方法签名里刻意用 {@link JSONArray} 而不是自定义的消息类型：
 * 会话历史与请求体在内存中同构，写入数据库时会转成二进制；
 * 中间再引入一层模型只会多一次转换和一处不一致。
 *
 * <p>实现里任何「一次请求」的状态（连接、取消句柄、累积器）都必须挂在
 * 该方法内部的局部对象上，不能放进字段 —— 否则两次请求会互相踩。
 * 唯一允许的字段是像 {@link HttpRequestTracker} 那样自身线程安全的注册表。
 */
public interface ModelProvider {

    /**
     * 发一次请求，返回组装好的助手回复。
     *
     * <p>流式增量通过 {@code listener} 同步回调；返回的
     * {@link AssistantTurn} 是完整结果（正文、思考、工具调用、用量）。
     *
     * @param config       本次请求的配置（地址、密钥、模型、协议参数）
     * @param systemPrompt 系统提示
     * @param messages     会话历史，各家协议就地转换成自己的报文格式
     * @param tools        可用工具的 JSON Schema；协议不支持工具时忽略
     * @param listener     增量回调，可为 null
     * @throws StreamFailure       传输层失败，带机器可读的失败码
     * @throws InterruptedException 请求被取消（用户中断或线程被 interrupt）
     */
    AssistantTurn createMessage(
        SessionConfig config,
        String systemPrompt,
        JSONArray messages,
        JSONArray tools,
        StreamListener listener
    ) throws Exception;

    /**
     * 中断当前由该线程发起的请求，如果这套传输能做到。
     *
     * <p>默认什么都不做，因为不是每种传输都有可中断的句柄。
     * 真正实现了的是阻塞式 HTTP 那三家：它们靠
     * {@link HttpRequestTracker} 找到该线程的连接并 {@code disconnect()}。
     *
     * @param worker 发起请求的工作线程，可为 null
     */
    default void cancelRequest(Thread worker) { }
}
