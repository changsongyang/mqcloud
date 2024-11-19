# 前置配置

1. <span id="pom">pom依赖</span>

   ```
   <dependency>
       <groupId>com.sohu.tv</groupId>
       <artifactId>${clientArtifactId}</artifactId>
       <version>${version}</version>
   </dependency>
   <repository>
       <id>sohu.nexus</id>
       <url>${repositoryUrl}</url>
   </repository>
   ```

2. <span id="logback">日志配置</span>

   在类路径添加日志配置文件[rmq.logback.xml](rmq.logback.xml)，名称不可更改，文件内容参考如下：

   ```
   <?xml version="1.0" encoding="UTF-8"?>
   <configuration>
       <appender name="rmqAppender" class="ch.qos.logback.core.rolling.RollingFileAppender">
           <file>${LOGS_DIR}/rocketmq.log</file>
           <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
               <fileNamePattern>${LOGS_DIR}/otherdays/rocketmq.log.%d{yyyy-MM-dd}</fileNamePattern>
               <maxHistory>40</maxHistory>
           </rollingPolicy>
           <encoder>
               <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} {%thread} %-5level %logger{50}-%L - %msg%n</pattern>
               <charset class="java.nio.charset.Charset">UTF-8</charset>
           </encoder>
       </appender>
       <root level="INFO">
           <appender-ref ref="rmqAppender" />
       </root>
   </configuration>
   ```

   无论项目中使用的是log4j还是lo4j2，都可用此方式配置RocketMQ的日志，因为RocketMQ内部已经集成了logback。

## 一、<span id="spring-boot">初始化之spring-boot方式</span>

```
@Configuration
public class MQConfiguration {
    @Value("${flushCache.producerGroup}")
    private String flushCacheProducer;

    @Value("${flushCache.topic}")
    private String flushCacheTopic;
    
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public ${producer} flushCacheProducer() {
        return new ${producer}(flushCacheProducer, flushCacheTopic);
    }
}
```

producerGroup和topic具体的值，请参考[topic详情页](topic#detail)，然后配置到yml或properties里。

## 二、<span id="spring-xml">初始化之spring xml方式</span>

```
<!-- 采用spring xml方式 -->			
<bean id="xxxProducer" class="com.sohu.tv.mq.rocketmq.${producer}" init-method="start" destroy-method="shutdown">
    <constructor-arg index="0" value="${请从topic详情查询生产者的producer group}"></constructor-arg>
    <constructor-arg index="1" value="${topic名字}"></constructor-arg>
</bean>
```

## 三、<span id="java">初始化之java方式</span>

```
// 生产者初始化 注意：只用初始化一次
${producer} producer = new ${producer}("xxx-producer", "xxx-topic");
// 注意，只用启动一次
producer.start();
// 应用退出时
producer.shutdown();
```

## 四、<span id="produceMessage">发送普通消息示例</span>：

1. <span id="produceJson">发送json消息</span>（建议申请topic时序列化方式选择为*String*）

   ```
   // 构建业务对象
   int id = 123;
   Video video = new Video();
   video.setId(id);
   // 转换为json
   String str = JSON.toJSONString(video);
   //建议设置keys(多个key用空格分隔)参数(也可以忽略该参数)，比如keys指定为id，那么就可以根据id查询消息
   Result<SendResult> sendResult = producer.publish(str, String.valueOf(id));
   if(!sendResult.isSuccess){
       //失败消息处理
   }
   ```

2. <span id="produceObject">发送对象</span>（要保证此topic仅仅自己使用，申请topic时序列化方式选择为*Protobuf*）

   ```
   // 构建业务对象
   int id = 123;
   Video video = new Video();
   video.setId(id);
   //建议设置keys(多个key用空格分隔)参数(也可以忽略该参数)，比如keys指定为id，那么就可以根据id查询消息
   Result<SendResult> sendResult = producer.publish(video, String.valueOf(id));
   if(!sendResult.isSuccess){
       //失败消息处理
   }
   ```

   如果采用*Protobuf*方式序列化，修改消息对象时需要注意如下事项：

   1. **已经存在的属性请勿删除。**
   2. **新增属性务必加到所有属性后边。**

   否则，可能导致消费者反序列化失败，无法消费消息。

   另外，**如果发送的对象包含jdk以外的类，请联系管理员做处理，否则`消息查询`模块会展示乱码**（*本质原因是MQCloud做反序列化时找不到相应的类导致的*）。

3. 发送map（申请topic时序列化方式选择为*Protobuf*）

   ```
   Map<String, Object> message = new HashMap<String, Object>();
   message.put("vid", "123456");
   message.put("aid", "789172");
   //建议设置keys(多个key用空格分隔)参数(也可以忽略该参数)，比如keys指定为vid，那么就可以根据vid查询消息
   Result<SendResult> sendResult = producer.publish(message);
   if(!sendResult.isSuccess){
       //失败消息处理
   }
   ```

   **注意**： Map中只能存放基本类型，请勿存放对象，否则MQCloud`消息查询`模块可能会展示成乱码。

   **如果Map中包含了jdk以外的类，请联系管理员做处理。**

4. 如何使用rocketmq官方的方式发送消息？

   ```
   producer.publish(Message message)
   ```

   具体可以参考rocketmq官方[demo](https://github.com/apache/rocketmq/blob/master/example/src/main/java/org/apache/rocketmq/example/quickstart/Producer.java)。

   此种方式发送消息跟使用原生rocketmq客户端一致，不会经过任何序列化。

   但是，消费者需要注意，需要单独设置setMessageSerializer(null)，否则消费消息会反序列化失败。

5. 发送消息如何进行<b id="asynRetry">异步重试</b>？

   *注：与[RocketMQ自身的重试](#retry)是不一样的，因为RocketMQ默认的重试机制是同步的，并存在超时而无法完成重试的可能。* 

   MQCloud在消息发送失败时，提供了异步重试api：

   ```
   Result<SendResult> sendResult = producer.send(MQMessage.build(msg).setKeys(key));
   if (!result.isSuccess && !result.isRetrying()) { // 发送失败并且没有正在重试认为失败
       System.out.println("发送失败");
   }
   ```

   另外，如果需要知道异步重试的结果，可以在producer初始化时进行如下设置：

   ```
   producer.setResendResultConsumer(result -> {
       if (!result.isSuccess) {
           logger.info("重试次数:{},消息:{}", result.getRetriedTimes(), result.getMqMessage());
           // 可以在这里增加重试失败的消息处理逻辑
       }
   });
   ```

   默认的重试次数为一次，可以通过如下api修改默认重试次数：

   ```
   producer.setDefaultRetryTimes(2)
   ```

   当然，如果想针对某条消息单独设置重试次数，可以参考如下，会覆盖默认重试次数：

   ```
   MQMessage.build(msg).setRetryTimes(3)
   ```

   异步重试使用的线程数默认为cpu核数，任务阻塞队列为100，如果想修改可以在producer.start之前，调用如下api修改：

   ```
   producer.setRetrySenderExecutor(ExecutorService retrySenderExecutor)
   ```

## 五、<span id="produceOrderMessage">发送有序消息示例</span>

```
/**
 * 相同的id发送到同一个队列
 * hash方法：id % 队列数
 */
class IDHashMessageQueueSelector implements MessageQueueSelector {
    public MessageQueue select(List<MessageQueue> mqs, Message msg, Object idObject) {
        long id = (Long) idObject;
        int size = mqs.size();
        int index = (int) (id % size);
        return mqs.get(index);
    }
}
// 设置到producer
producer.setMessageQueueSelector(new IDHashMessageQueueSelector());
// 消息发送
long id = 123L;
Map<String, Object> map = new HashMap<String, Object>();
map.put("id", id);
Result<SendResult> sendResult = null;
do {
    sendResult = producer.publishOrder(map, String.valueOf(id), id);
    if (!sendResult.isSuccess()) {
    	Thread.sleep(1000);
    }
} while (!sendResult.isSuccess()); // 发送失败可以进行重试或者自行降级处理
```

**注意：此种发送方式不带[重试机制](#retry)。**

## 六、 <span id="produceTransMessage">发送事务消息示例</span>

```
// 1.定义实现事务回调接口
TransactionListener transactionListener = new TransactionListener() {
    /**
     * 在此方法执行本地事务
     */
    public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        // arg可以传业务id
        int id = (Integer) arg;
        // 确定事务状态，未知返回：UNKNOW，回滚返回：ROLLBACK_MESSAGE，成功返回：COMMIT_MESSAGE，抛出异常默认为：UNKNOW
        return LocalTransactionState.COMMIT_MESSAGE;
    }

    /**
     * 如果executeLocalTransaction返回UNKNOW，rocketmq会回调此方法查询事务状态，默认每分钟查一次，最多查询15次，状态还是UNKNOW的话，丢弃消息
     */
    public LocalTransactionState checkLocalTransaction(MessageExt msg) {
        String key = msg.getKeys();
        int id = Integer.valueOf(key);
        return LocalTransactionState.COMMIT_MESSAGE;
    }
};

// 2.发送事务消息
// 初始化
${producer} producer = new ${producer}(producerGroup, topic, transactionListener);
// 组装消息
int id = 123;
Map<String, Object> map = new HashMap<String, Object>();
map.put("id", id);
map.put("msg", "msg" + id);
// 发送
Result<SendResult> sendResult = producer.publishTransaction(JSON.toJSONString(map), String.valueOf(id), id);
if(!sendResult.isSuccess){
    //失败消息处理
}
```

##  七、 <span id="sentinel">隔离发送消息示例</span>

```
// 设置启用熔断器
producer.setEnableCircuitBreaker(true);
// 设置熔断降级回调
producer.setCircuitBreakerFallbackConsumer(new Consumer<MQMessage>() {
    public void accept(MQMessage message) {
        // 在这里处理熔断时发送失败的消息
    }
});
// 调用如下接口发送消息
Result<SendResult> sendResult = producer.send(MQMessage.build(msg));
```

注意：需要显示添加如下依赖：

```
<dependency>
    <groupId>com.alibaba.csp</groupId>
    <artifactId>sentinel-core</artifactId>
    <version>1.8.8</version>
</dependency>
```

另外，默认的熔断规则如下：

```
1.慢调用熔断规则
20秒内，响应超过1秒的请求数量达到20个，并且达到总请求量的60%，则熔断10秒。
2.异常调用熔断规则
60秒内，异常请求数量达到5个，并且达到总请求数量的80%，则熔断10秒。
其中，异常类型如下：
org.apache.rocketmq.remoting.exception.RemotingException: 远程异常
org.apache.rocketmq.client.exception.MQBrokerException: broker明确响应异常，包括限流等
```

如果需要自定义熔断规则，请参考sentinel官方文档。

## 八、<span id="sync">同步发送消息问题</span>

### 1.发送一条消息需要哪些步骤？

1. 检查处理

   比如客户端是否启动，消息序列化，消息大小是否超过4M等。

2. topic路由查询

   消息归属于topic，topic是一类消息的合集，那么首先需要知道topic在哪些broker上。

3. broker选择

   因为集群中有多个broker，需要挑选一个健康的broker进行消息发送。

4. 获取broker主节点

   broker一般都是主备两个节点，消息只能发送到主节点。

5. 一些发送前钩子调用

6. 获取netty通道

7. 调用通道发送消息

8. 等待响应(默认为sendMsgTimeout=3秒)

9. 处理响应及异常

### 2.发送消息的过程中，有哪些地方可能会产生较长的耗时？

1. topic路由的查询

   topic的路由需要从name server上查询，此过程是远程调用，超时默认设置的是3秒。

2. 获取netty通道

   相当于建立链接，默认超时时间3秒。

3. 发送消息并等待响应(默认为sendMsgTimeout=3秒)

对于以上三种耗时，

第一，其中`topic路由的查询`客户端启动后10毫秒会自动缓存topic路由，之后每隔30秒更新一次，所以`topic路由的查询`一般来说不会影响消息发送。

第二，对于`获取netty通道`也仅限于第一次消息发送，因为netty是长链，一旦建立会自动缓存，后续通过心跳机制来保障链接的连通性。

第三，其实耗时基本是在`发送消息并等待响应`。

### <span id="retry">3.</span>默认的消息重试机制是怎样的？

1. 针对发送失败的消息，后续会最多进行2次重试（可以通过设置retryTimesWhenSendFailed修改）

2. 为什么说最多2次重试呢，因为如果发送耗时达到sendMsgTimeout也会中断重试机制。

   那如果把sendMsgTimeout设长是否会一定重试2次呢？这个不一定，因为第一次调用有可能一直等sendMsgTimeout的时间，就没有第二次重试的机会了。

   **MQCloud针对此种情况进行了修改，增加了单次请求最大耗时参数的设置，默认总耗时设置为4秒，单次请求最大为3秒，这样至少保证broker无响应时重试一次。**

### 4.当broker集群中某个节点不可用时，客户端能否自动剔除？

可以的，默认mq-client-open开启了rocketmq的容错机制，即它通过统计每次发送消息到broker的耗时和异常情况，检测出哪些broker响应情况不好，从而避免向这些broker发送消息。

### 5.发送消息的过程中，会产生哪些异常？

1. MQClientException

   客户端的问题，比如客户端未启动，消息过大，空消息，配置错误等等。

2. RemotingTooMuchRequestException

   真实发送前超时检测，如果已超时，直接抛出异常。

3. RemotingSendRequestException

   请求发送失败。

4. RemotingTimeoutException

   1. 请求未发送，`topic路由查询`或`通道连接`阶段已经超时。
   2. 请求发送正常，超时时间内broker没有返回值。

5. RemotingConnectException

   通道无法连接，请求未发送。

6. MQBrokerException

   响应的code中除了成功，刷盘超时，同步slave超时，slave不可用以外的值都认为是MQBrokerException。比如`org.apache.rocketmq.client.exception.MQBrokerException: CODE: 2 DESC: [TIMEOUT_CLEAN_QUEUE]broker busy, start flow control for a while`就是broker流控导致的。

7. InterruptedException

   发送线程被中断。

### 6.如果消息不可丢失，如何保证100%发送成功？

发送消息后**打印消息及Result对象**，及**检查返回值**，是否成功，如果发送失败，进行重试或者降级操作，比如把失败的消息存储到数据库，定时补发。

另外，`Result.isSuccess()`只是表明此次发送成功了，具体消息存储状态还取决于`Result.getResult().getSendStatus()`里的值(以下参考来自rocketmq官方文档)： 

```
1. SEND_OK
消息发送成功
2. FLUSH_DISK_TIMEOUT
消息接收成功，但是服务器刷盘超时，消息已经进入服务器队列，只有此时服务器宕机，消息才会丢失。
3. FLUSH_SLAVE_TIMEOUT
消息接收成功，但是服务器同步到 Slave 时超时，消息已经进入服务器队列，只有此时服务器宕机，消息才会丢失。
4. SLAVE_NOT_AVAILABLE
消息接送成功，但是此时 slave 不可用，消息已经进入服务器队列，只有此时服务器宕机，消息才会丢
失。
```

**以上状态值跟broker设置相关，如果确定需要最高级别保障消息不可丢失，请申请topic时勾选上`支持事务`选项，将会在事务集群上创建该topic。**

## 九、<span id="async">异步发送消息问题</span>

### 1.与同步发送有哪些不同的地方？

1. 异步发送会使用独立的线程池来发送，不会阻塞业务线程，默认线程池配置简化如下：

   ```
   this.asyncSenderThreadPoolQueue = new LinkedBlockingQueue<Runnable>(50000);
   this.defaultAsyncSenderExecutor = new ThreadPoolExecutor(
       Runtime.getRuntime().availableProcessors(),
       Runtime.getRuntime().availableProcessors(),
       1000 * 60,
       TimeUnit.MILLISECONDS,
       this.asyncSenderThreadPoolQueue);
   ```

   可以通过`DefaultMQProducerImpl.setAsyncSenderExecutor(ExecutorService asyncSenderExecutor)`来设置自己的线程池。

   由于默认线程池使用有界队列，所以可能存在任务等待或被拒绝的情况：

   1. 如果执行任务时等待时间超过了sendMsgTimeout（默认为3秒），那么任务将不会执行直接返回。
   2. 如果线程池满了，将直接抛出`MQClientException("executor rejected ", e)`。

2. 异步发送通过信号量来做流控，默认最大控制的并发请求数为65535。

3. 异步发送重试逻辑与同步发送类似，重试次数默认为retryTimesWhenSendAsyncFailed=2，可以修改配置。

4. 由于异步发送是通过回调检测返回值和异常的，参见如下：

   ```
   public interface SendCallback {
       void onSuccess(final SendResult sendResult);
       void onException(final Throwable e);
   }
   ```

   在`onSuccess`中务必检查返回值，与同步发送类似。

   在`onException`中处理异常。

5. 其余与同步发送基本相同。
## 十、<span id="oneway">oneway发送消息问题</span>

### 1.与异步发送有哪些不同的地方？

使用业务线程发送，没有重试机制，不等待响应。

## 十一、<span id="delay">延迟消息</span>

延迟消息是指：延迟到固定时间后可以消费的消息。

*注意：延迟消息与定时消息的区别是，延迟消息只支持固定的延迟时间，而定时消息则支持任意的时间延迟。*

延迟消息固定的延迟级别如下：

```
LEVEL_1_SECOND(1, "1秒")
LEVEL_5_SECONDS(2, "5秒")
LEVEL_10_SECONDS(3, "10秒")
LEVEL_30_SECONDS(4, "30秒")
LEVEL_1_MINUTE(5, "1分钟")
LEVEL_2_MINUTES(6, "2分钟")
LEVEL_3_MINUTES(7, "3分钟")
LEVEL_4_MINUTES(8, "4分钟")
LEVEL_5_MINUTES(9, "5分钟")
LEVEL_6_MINUTES(10, "6分钟")
LEVEL_7_MINUTES(11, "7分钟")
LEVEL_8_MINUTES(12, "8分钟")
LEVEL_9_MINUTES(13, "9分钟")
LEVEL_10_MINUTES(14, "10分钟")
LEVEL_20_MINUTES(15, "20分钟")
LEVEL_30_MINUTES(16, "30分钟")
LEVEL_1_HOUR(17, "1小时")
LEVEL_2_HOURS(18, "2小时")
```

使用方式如下：

```
// 5分钟后投递消息
MQMessage<?> mqMessage = MQMessage.build(msg).setDelayTimeLevel(MessageDelayLevel.LEVEL_5_MINUTES.getLevel());
Result<SendResult> sendResult = producer.send(mqMessage);
if (!sendResult.isSuccess) { // 发送失败
    System.out.println("发送失败");
}
```



## 十二、<span id="timerWheel">定时消息</span>

定时消息是指：延迟到指定时间后可以消费的消息。

*注意：定时消息与延迟消息的区别是，定时消息则支持任意的时间延迟，而延迟消息只支持固定的延迟时间。*

目前最大支持30天的定时，使用方式如下：

```
// 24小时后投递消息
long deliveryTimestamp = System.currentTimeMillis() + (24 * 3600 * 1000L);
MQMessage<?> mqMessage = MQMessage.build(msg).setKeys(key).setDeliveryTimestamp(deliveryTimestamp);
Result<SendResult> sendResult = producer.send(mqMessage);
if (!sendResult.isSuccess) { // 发送失败
    System.out.println("发送失败");
}
```

#### 注意：
- 消息的过期时间尽量分散，不建议大量投递同一时间到期的消息。
- 少数特殊情况下会产生重复消息，业务端需自行实现幂等

取消定时消息目前有两种途径：

1. 页面取消

   [消息查询-定时消息](messageQuery#queryWheelMessage)页面支持点击<i class="fas fa-stopwatch"></i>取消定时消息。

2. 接口取消

   接口取消的前提是，自行保存msgId ：获取方式`sendResult.getResult().getMsgId()` 。

   1. 接口地址：
      ```
       POST /topic/message/cancelWheelMsg
      ```
   2. 接口参数： 
      * topic：消息主题
      * uniqIds：消息唯一id(msgId)，多个id用逗号分隔，单次最多支持20个id
      * token：验证token，可咨询管理员获取

   3. 响应说明：
      ```
      {
       "status": 200, 
       "message": 
      }
      ```
      1. status：：标识本次响应的状态码，包括但不限于如下值：
         * status：300 参数错误，topic不存在
         * status：303 权限不足，无法取消
         * status：705 uniqId无效，无法定位消息
         * status：706 uniqid对应的消息已超出取消时间范围，无法取消
         * status：707 uniqid对应的消息为非时间轮定时消息，无法取消
         * status：708 uniqid的取消申请已存在，不能重复申请
         * status：200 取消成功
      2. message：当响应状态码非200时的提示信息。

   4. 生产示例：
     ```
      // 设置请求头
      HttpHeaders headers = new HttpHeaders();
      headers.add("Cookie", "TOKEN=" + token);
      headers.setContentType(MediaType.MULTIPART_FORM_DATA);
      // 设置请求参数
      MultiValueMap<String, String> multiValueMap = new LinkedMultiValueMap<>();
      multiValueMap.add("topic", "basic-delay-cancel-topic");
      multiValueMap.add("uniqIds", uniqIds);
      // 发送POST请求
      HttpEntity httpEntity = new HttpEntity<>(multiValueMap, headers);
      ResponseEntity<WebResult> response =
              restTemplate.postForEntity(CANCEL_DELAY_URL, httpEntity, WebResult.class);
      WebResult body = response.getBody();
      if (body.ok()) {
          log.info("取消定时消息成功, uniqIds:{}", uniqIds);
      }
     ```
#### 注意：
- 能被取消的定时消息的定时时间需大于当前时间5分钟以上，如需取消小于该范围的消息，请联系管理员。
- 该功能能保障绝大部分情况下的取消，但仍有极少数情况下无法取消，如：
  - 集群机器不可用，取消消息写入失败，定时无法取消
  - MQCloud服务不可用，取消消息发送失败，定时无法取消
  - 网络故障，取消消息无法在定时消息触发前发送，定时无法取消

  如需严格保证，请先咨询管理员。
- 该功能仅支持rocketmq 5.x版本的时间轮定时消息。