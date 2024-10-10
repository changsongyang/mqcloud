package com.sohu.tv.mq.cloud.web.controller;

import com.sohu.tv.mq.cloud.bo.*;
import com.sohu.tv.mq.cloud.bo.Audit.TypeEnum;
import com.sohu.tv.mq.cloud.common.util.CipherHelper;
import com.sohu.tv.mq.cloud.mq.DefaultCallback;
import com.sohu.tv.mq.cloud.mq.MQAdminTemplate;
import com.sohu.tv.mq.cloud.service.*;
import com.sohu.tv.mq.cloud.util.*;
import com.sohu.tv.mq.cloud.common.util.WebUtil;
import com.sohu.tv.mq.cloud.web.controller.param.PaginationParam;
import com.sohu.tv.mq.cloud.web.vo.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.remoting.protocol.body.ClusterInfo;
import org.apache.rocketmq.tools.admin.MQAdminExt;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户接口
 * 
 * @Description:
 * @author yongfeigao
 * @date 2018年6月12日
 */
@Controller
@RequestMapping("/user")
public class UserController extends ViewController {

    @Autowired
    private UserService userService;

    @Autowired
    private TopicService topicService;

    @Autowired
    private TopicTrafficService topicTrafficService;

    @Autowired
    private ConsumerTrafficService consumerTrafficService;

    @Autowired
    private ConsumerService consumerService;

    @Autowired
    private UserProducerService userProducerService;

    @Autowired
    private MQAdminTemplate mqAdminTemplate;

    @Autowired
    private ProducerTotalStatService producerTotalStatService;

    @Autowired
    private ClusterService clusterService;

    @Autowired
    private DelayMessageService delayMessageService;

    @Autowired
    private AuditService auditService;
    
    @Autowired
    private AlertService alertService;
    
    @Autowired
    private MQCloudConfigHelper mqCloudConfigHelper;
    
    @Autowired
    private UserWarnService userWarnService;
    
    @Autowired
    private CipherHelper cipherHelper;

    @Autowired
    private UserFootprintService userFootprintService;

    @Autowired
    private UserFavoriteService userFavoriteService;

    /**
     * 退出登录
     * 
     * @param topicParam
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping("/logout")
    public Result<?> logout(UserInfo userInfo, HttpServletResponse response) throws Exception {
        WebUtil.deleteLoginCookie(response);
        return Result.getOKResult();
    }

    @RequestMapping(value = "/resetPassword", method = RequestMethod.GET)
    public String resetPassword(Map<String, Object> map) throws Exception {
        setView(map, "resetPassword", "密码重置");
        return view();
    }

    /**
     * 用户密码重置
     * 
     * @param uid
     * @param passwordOld
     * @param passwordNew
     * @return
     */
    @ResponseBody
    @RequestMapping(value = "/resetPassword", method = RequestMethod.POST)
    public Result<?> resetPassword(@RequestParam("uid") int uid,
            @RequestParam("passwordOld") String passwordOld,
            @RequestParam("passwordNew") String passwordNew, HttpServletResponse response) {
        if (uid < 0 || passwordOld == "" || passwordNew == "") {
            return Result.getResult(Status.PARAM_ERROR);
        }
        // 校验老密码是否正确
        Result<User> userResult = userService.query(uid);
        if (userResult.isNotOK()) {
            return userResult;
        }
        String password = userResult.getResult().getPassword();
        if (password != null && password != "" && !DigestUtils.md5Hex(passwordOld).equals(password)) {
            return Result.getResult(Status.OLD_PASSWORD_ERROR);
        }
        Result<Integer> result = userService.resetPassword(uid, passwordNew);
        WebUtil.deleteLoginCookie(response);
        return result;
    }

    /**
     * 获取user列表
     * 
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public Result<?> list(UserInfo userInfo) throws Exception {
        Result<List<User>> userListResult = userService.queryAll();
        return Result.getWebResult(userListResult);
    }

    /**
     * 更新用户信息
     * @param map
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/update", method = RequestMethod.GET)
    public String updatePage(Map<String, Object> map) throws Exception {
        setView(map, "update", "我的资料");
        return view();
    }

    /**
     * 更新用户信息
     * 
     * @param topicParam
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/update", method = RequestMethod.POST)
    public Result<?> update(UserInfo userInfo, @Valid User userParam) throws Exception {
        if (!userInfo.getUser().getEmail().equals(userParam.getEmail())) {
            logger.warn("not equal! cookie user:{}, param:{}", userInfo.getUser().getEmail(), userParam.getEmail());
            return Result.getResult(Status.PERMISSION_DENIED_ERROR);
        }
        // 这里不允许更改用户类型
        userParam.setType(-1);
        Result<Integer> rst = userService.update(userParam);
        return Result.getWebResult(rst);
    }

    /**
     * 获取用户的topic
     * 
     * @return
     * @throws Exception
     */
    @RequestMapping("/topic")
    public String topic(UserInfo userInfo, @Valid PaginationParam paginationParam,
            @RequestParam(name = "topic", required = false) String queryTopic,
            Map<String, Object> map) throws Exception {
        // 设置返回视图
        setView(map, "topic", "Topic列表");
        // 设置分页参数
        setPagination(map, paginationParam);
        // 解析查询参数
        if (queryTopic != null) {
            queryTopic = queryTopic.trim();
            if (queryTopic.length() == 0) {
                queryTopic = null;
            }
        }
        // 设置查询参数
        TopicTrafficHolderVO topicTrafficHolderVO = new TopicTrafficHolderVO();
        topicTrafficHolderVO.setQueryTopic(queryTopic);
        setResult(map, topicTrafficHolderVO);

        List<Integer> traceClusterIdList = clusterService.getTraceClusterIdList();
        // 获取topic列表数量
        Result<Integer> countResult = topicService.queryTopicCount(queryTopic, userInfo.getUser(), traceClusterIdList);
        if (!countResult.isOK()) {
            return view();
        }
        paginationParam.caculatePagination(countResult.getResult());
        // 获取topic列表
        Result<List<Topic>> result = topicService.queryTopicList(queryTopic, userInfo.getUser(),
                paginationParam.getBegin(), paginationParam.getNumOfPage(), traceClusterIdList);
        if (result.isEmpty()) {
            return view();
        }
        List<Topic> topicList = result.getResult();
        // 组装topic id 列表
        List<Long> tidList = new ArrayList<Long>(topicList.size());
        List<Long> delayTidList = new ArrayList<Long>();
        for (Topic topic : topicList) {
            if (topic.delayEnabled()) {
                delayTidList.add(topic.getId());
            } else {
                tidList.add(topic.getId());
            }
        }
        // 获取一分钟之前的topic流量数据
        Date oneMinuteAgo = new Date(System.currentTimeMillis() - 60000);
        String time = DateUtil.getFormat(DateUtil.HHMM).format(oneMinuteAgo);
        Result<List<TopicTraffic>> topicTrafficListResult = Result
                .getResult(new ArrayList<TopicTraffic>(topicList.size()));
        if (!tidList.isEmpty()) {
            Result<List<TopicTraffic>> trafficListResult = topicTrafficService.query(tidList, oneMinuteAgo, time);
            if (trafficListResult.isNotEmpty()) {
                topicTrafficListResult.getResult().addAll(trafficListResult.getResult());
            }
        }
        if (!delayTidList.isEmpty()) {
            Result<List<TopicTraffic>> delayTrafficListResult = delayMessageService.query(delayTidList,
                    DateUtil.format(oneMinuteAgo), time);
            if (delayTrafficListResult.isNotEmpty()) {
                topicTrafficListResult.getResult().addAll(delayTrafficListResult.getResult());
            }
        }
        tidList.addAll(delayTidList);
        // 查询consumer列表
        Result<List<Consumer>> consumerListResult = consumerService.queryByTidList(tidList);
        Map<Long, List<Long>> consumerMap = null;
        if (consumerListResult.isNotEmpty()) {
            consumerMap = groupConsumer(consumerListResult.getResult());
        }

        // 查询生产者
        Set<Long> userTopicSet = userTopicSet(userInfo.getUser());

        // 组装vo
        List<TopicTrafficVO> topicTrafficVOList = new ArrayList<TopicTrafficVO>(topicList.size());
        for (Topic topic : topicList) {
            TopicTrafficVO topicTrafficVO = new TopicTrafficVO();
            BeanUtils.copyProperties(topic, topicTrafficVO);
            if (userInfo.getUser().isAdmin() || userTopicSet.contains(topic.getId())) {
                topicTrafficVO.setOwn(true);
            }
            topicTrafficVOList.add(topicTrafficVO);
            // 设置topic流量
            if (topicTrafficListResult.isNotEmpty()) {
                Traffic traffic = findTraffic(topic.getId(), topicTrafficListResult.getResult());
                topicTrafficVO.setTopicTraffic(traffic);
            }
            // 设置consumer流量
            if (consumerMap == null) {
                continue;
            }
            List<Long> cidList = consumerMap.get(topic.getId());
            // 查询consumer流量
            Result<List<ConsumerTraffic>> consumerTrafficListResult = consumerTrafficService.query(cidList, oneMinuteAgo, time);
            if (consumerTrafficListResult.isEmpty()) {
                continue;
            }
            List<ConsumerTraffic> consumerTrafficList = consumerTrafficListResult.getResult();
            // 组装consumer流量
            Traffic traffic = new Traffic();
            for (ConsumerTraffic consumerTraffic : consumerTrafficList) {
                traffic.addCount(consumerTraffic.getCount());
                traffic.addSize(consumerTraffic.getSize());
            }
            topicTrafficVO.setConsumerTraffic(traffic);
        }
        if (topicTrafficVOList.isEmpty()) {
            return view();
        }

        topicTrafficHolderVO.setTopicTrafficVOList(topicTrafficVOList);
        return view();
    }

    /**
     * 获取用户所属的topic
     * 
     * @return
     */
    private Set<Long> userTopicSet(User user) {
        if (user.isAdmin()) {
            return null;
        }
        // 查询生产者
        Result<List<UserProducer>> userProducerListResult = userProducerService.queryUserProducer(user.getId());
        Set<Long> set = new HashSet<Long>();
        if (userProducerListResult.isEmpty()) {
            return set;
        }
        for (UserProducer up : userProducerListResult.getResult()) {
            set.add(up.getTid());
        }
        return set;
    }

    /**
     * 将消费者id按照topic id分组
     * 
     * @param consumerList
     * @return
     */
    private Map<Long, List<Long>> groupConsumer(List<Consumer> consumerList) {
        Map<Long, List<Long>> map = new HashMap<Long, List<Long>>();
        for (Consumer consumer : consumerList) {
            List<Long> consumerIdList = map.get(consumer.getTid());
            if (consumerIdList == null) {
                consumerIdList = new ArrayList<Long>();
                map.put(consumer.getTid(), consumerIdList);
            }
            consumerIdList.add(consumer.getId());
        }
        return map;
    }

    /**
     * 查找topic流量
     * 
     * @param tid
     * @param topicTrafficList
     * @return
     */
    private Traffic findTraffic(long tid, List<TopicTraffic> topicTrafficList) {
        for (TopicTraffic topicTraffic : topicTrafficList) {
            if (tid == topicTraffic.getTid()) {
                return topicTraffic;
            }
        }
        return null;
    }

    /**
     * 获取用户的topic 详情
     * 
     * @param topicParam
     * @return
     * @throws Exception
     */
    @RequestMapping("/topic/{tid}/detail")
    public String detail(UserInfo userInfo, @PathVariable long tid, Map<String, Object> map) throws Exception {
        setView(map, "topicDetail", "我的Topic");
        setResult(map, "tid",tid);
        return view();
    }

    /**
     * 获取用户的topic topology
     * 
     * @return
     * @throws Exception
     */
    @RequestMapping("/topic/{tid}/topology")
    public String topology(UserInfo userInfo, @PathVariable long tid, @Valid PaginationParam paginationParam,
            Map<String, Object> map) throws Exception {
        Result<TopicTopology> result = userService.queryTopicTopology(userInfo.getUser(), tid);
        setPagination(map, paginationParam);
        if (result.isOK()) {
            Topic topic = result.getResult().getTopic();
            topic.setCluster(clusterService.getMQClusterById(topic.getClusterId()));
            // 获取一分钟之前的流量数据
            Date oneMinuteAgo = new Date(System.currentTimeMillis() - 60000);
            String time = DateUtil.getFormat(DateUtil.HHMM).format(oneMinuteAgo);
            List<Long> tidList = new ArrayList<Long>(1);
            tidList.add(topic.getId());
            Result<List<TopicTraffic>> topicTrafficListResult = null;
            if (topic.delayEnabled()) {
                topicTrafficListResult = delayMessageService.query(tidList, DateUtil.format(oneMinuteAgo), time);
            } else {
                topicTrafficListResult = topicTrafficService.query(tidList, oneMinuteAgo, time);
            }

            if (topicTrafficListResult.isNotEmpty()) {
                result.getResult().setTopicTraffic(topicTrafficListResult.getResult().get(0));
            }
            // 获取consumer流量
            List<Consumer> consumerList = result.getResult().getConsumerList();
            List<Long> cidList = new ArrayList<Long>();
            if (consumerList != null && consumerList.size() > 0) {
                // 分页
                pagination(result.getResult(), paginationParam);
                
                for (Consumer c : consumerList) {
                    cidList.add(c.getId());
                }
                Result<List<ConsumerTraffic>> consumerTrafficListResult = consumerTrafficService.query(cidList, oneMinuteAgo,
                        time);
                if (consumerTrafficListResult.isNotEmpty()) {
                    for (Consumer c : consumerList) {
                        for (ConsumerTraffic ct : consumerTrafficListResult.getResult()) {
                            if (c.getId() == ct.getConsumerId()) {
                                c.setConsumerTraffic(ct);
                                break;
                            }
                        }
                    }
                }
            }

            // 获取集群信息
            ClusterInfo clusterInfo = mqAdminTemplate.execute(new DefaultCallback<ClusterInfo>() {
                public ClusterInfo callback(MQAdminExt mqAdmin) throws Exception {
                    return mqAdmin.examineBrokerClusterInfo();
                }

                public Cluster mqCluster() {
                    return clusterService.getMQClusterById(topic.getClusterId());
                }
            });
            int brokerSize = clusterInfo == null ? 0 : clusterInfo.getBrokerAddrTable().size();
            result.getResult().setBrokerSize(brokerSize);
            // 获取topic生产者
            List<UserProducer> upList = result.getResult().getPrevProducerList();
            if (upList != null && upList.size() > 0) {
                List<Long> uidList = new ArrayList<Long>();
                for (UserProducer up : upList) {
                    uidList.add(up.getUid());
                }
                Result<List<User>> userListResult = userService.query(uidList);
                if (userListResult.isNotEmpty()) {
                    Map<StatsProducer, List<UserProducer>> filterMap = result.getResult().getProducerFilterMap();
                    int producerHasTrafficCount = 0;
                    for (StatsProducer statsProducer : filterMap.keySet()) {
                        for (UserProducer up : filterMap.get(statsProducer)) {
                            for (User u : userListResult.getResult()) {
                                if (up.getUid() == u.getId()) {
                                    up.setUsername(u.getName() == null ? u.getEmailName() : u.getName());
                                    break;
                                }
                            }
                            if (up.getUsername() == null) {
                                up.setUsername("deleted:" + up.getUid());
                            }
                        }
                        // 查询是否有流量统计
                        Result<Boolean> statResult = producerTotalStatService.query(statsProducer.getProducer());
                        statsProducer.setStats(statResult.isOK() && statResult.getResult());
                        if (statsProducer.isStats()) {
                            ++producerHasTrafficCount;
                        }
                    }
                    // 多于一个生产者需要统计各个生产者的流量
                    if (filterMap.size() > 1 && producerHasTrafficCount > 0) {
                        int statTime = (int) ((System.currentTimeMillis() - 60000) / 60000);
                        for (StatsProducer statsProducer : filterMap.keySet()) {
                            if(!statsProducer.isStats()) {
                                continue;
                            }
                            result.getResult().setProducerHasTraffic(true);
                            Result<Integer> rst = producerTotalStatService.query(statsProducer.getProducer(), statTime);
                            if (rst.isOK()) {
                                Integer count = rst.getResult();
                                if (count != null && count > 0) {
                                    if (result.getResult().getTopicTraffic() != null) {
                                        statsProducer.copyTraffic(result.getResult().getTopicTraffic());
                                    }
                                    if (statsProducer.getTraffic() != null) {
                                        statsProducer.getTraffic().setCount(count);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 获取总流量
            Result<TopicTraffic> topicTrafficResult = null;
            if (topic.delayEnabled()) {
                topicTrafficResult = delayMessageService.query(topic.getId(), DateUtil.format(oneMinuteAgo));
            } else {
                topicTrafficResult = topicTrafficService.queryTotalTraffic(topic.getId(), oneMinuteAgo);
            }
            if (topicTrafficResult.isOK()) {
                result.getResult().setTotalTopicTraffic(topicTrafficResult.getResult());
            }
            if (cidList != null && cidList.size() > 0) {
                Result<ConsumerTraffic> consumerTrafficResult = consumerTrafficService.queryTotalTraffic(cidList, oneMinuteAgo);
                if (consumerTrafficResult.isOK()) {
                    result.getResult().setTotalConsumerTraffic(consumerTrafficResult.getResult());
                }
            }

            // 记录访问足迹
            UserFootprint userFootprint = new UserFootprint();
            userFootprint.setUid(userInfo.getUser().getId());
            userFootprint.setTid(tid);
            userFootprintService.save(userFootprint);

            // 判断是否收藏
            Result<UserFavorite> userFavoriteResult = userFavoriteService.query(userInfo.getUser().getId(), tid);
            if (userFavoriteResult.isOK()) {
                result.getResult().setFavoriteId(userFavoriteResult.getResult().getId());
            }

            // 设置全局顺序topic kv配置
            if (topic.isOrderedTopic()) {
                result.getResult().setOrderTopicKVConfig(mqCloudConfigHelper.getOrderTopicKVConfig(String.valueOf(topic.getClusterId())));
            }
        }

        setResult(map, result);
        FreemarkerUtil.set("splitUtil", SplitUtil.class, map);
        setResult(map, "mqcloudDomain", mqCloudConfigHelper.getDomain());
        return viewModule() + "/topicTopology";
    }
    
    /**
     * 分页
     * @param topicTopology
     * @param paginationParam
     * @param map
     */
    private void pagination(TopicTopology topicTopology, PaginationParam paginationParam) {
        List<Consumer> consumerList = topicTopology.getConsumerList();
        paginationParam.caculatePagination(consumerList.size());
        topicTopology.setConsumerList(consumerList.subList(paginationParam.getBegin(), paginationParam.getEnd()));
    }

    /**
     * 获取用户的topic及关联的生产者和消费者
     * 
     * @param topicParam
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/batch/associate", method = RequestMethod.GET)
    public String batchAssociate(UserInfo userInfo, Map<String, Object> map) throws Exception {
        setView(map, "batchAssociate", "批量授权");

        // 获取生产者
        Result<List<UserProducer>> userProducerListResult = userProducerService
                .queryUserProducer(userInfo.getUser().getId());

        // 获取消费者
        Result<List<Consumer>> consumerListResult = consumerService.queryUserConsumer(userInfo.getUser().getId());

        // 定义各个角色集合
        Set<Long> topicIdSet = new HashSet<>();

        // 获取topicId
        if (userProducerListResult.isNotEmpty()) {
            for (UserProducer up : userProducerListResult.getResult()) {
                topicIdSet.add(up.getTid());
            }
        }

        // 获取topicId
        if (consumerListResult.isNotEmpty()) {
            for (Consumer consumer : consumerListResult.getResult()) {
                topicIdSet.add(consumer.getTid());
            }
        }

        if (topicIdSet.isEmpty()) {
            return view();
        }

        // 获取topic列表
        Result<List<Topic>> topicListResult = topicService.queryTopicList(topicIdSet);

        if (topicListResult.isEmpty()) {
            return view();
        }

        // 拼装vo
        List<TopicInfoVO> topicInfoVOList = new ArrayList<>();
        for (Topic topic : topicListResult.getResult()) {
            TopicInfoVO ti = new TopicInfoVO();
            ti.setTopic(topic);
            addUserProducer(ti, userProducerListResult.getResult());
            addConsumer(ti, consumerListResult.getResult());
            topicInfoVOList.add(ti);
        }

        setResult(map, topicInfoVOList);
        return view();
    }

    /**
     * 批量关联
     * 
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/batch/associate", method = RequestMethod.POST)
    public Result<?> batchAssociatePost(UserInfo userInfo, @RequestParam("uids") String uids,
            @RequestParam("producerIds") String producerIds, @RequestParam("consumerIds") String consumerIds,
            Map<String, Object> map) throws Exception {
        if (StringUtils.isEmpty(uids) || (StringUtils.isEmpty(producerIds) && StringUtils.isEmpty(consumerIds))) {
            return Result.getResult(Status.PARAM_ERROR);
        }

        // 构建Audit
        Audit audit = new Audit();
        audit.setType(Audit.TypeEnum.BATCH_ASSOCIATE.getType());
        audit.setStatus(Audit.StatusEnum.INIT.getStatus());
        audit.setUid(userInfo.getUser().getId());
        // 构建AuditBatchAssociate
        AuditBatchAssociate auditBatchAssociate = new AuditBatchAssociate();
        auditBatchAssociate.setUids(uids);
        if (StringUtils.isNotEmpty(producerIds)) {
            auditBatchAssociate.setProducerIds(producerIds);
        }
        if (StringUtils.isNotEmpty(consumerIds)) {
            auditBatchAssociate.setConsumerIds(consumerIds);
        }

        Result<Audit> result = auditService.saveAuditAndAssociateBatch(audit, auditBatchAssociate);
        if (result.isOK()) {
            alertService.sendAuditMail(userInfo.getUser(), TypeEnum.BATCH_ASSOCIATE, "");
        }
        return Result.getWebResult(result);
    }

    private void addUserProducer(TopicInfoVO ti, List<UserProducer> list) {
        if (list == null) {
            return;
        }
        for (UserProducer up : list) {
            if (up.getTid() == ti.getTopic().getId()) {
                ti.addUserProducer(up);
            }
        }
    }

    private void addConsumer(TopicInfoVO ti, List<Consumer> list) {
        if (list == null) {
            return;
        }
        for (Consumer consumer : list) {
            if (consumer.getTid() == ti.getTopic().getId()) {
                ti.addConsumer(consumer);
            }
        }
    }
    
    /**
     * 获取用户的topic状况
     * 
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping("/topic/stat")
    public Result<?> topicStat(UserInfo userInfo) throws Exception {
        List<Integer> traceClusterIdList = clusterService.getTraceClusterIdList();
        Result<TopicStat> rst = topicService.queryTopicStat(userInfo.getUser(), traceClusterIdList);
        return Result.getWebResult(rst);
    }
    
    /**
     * 用户警告
     * @param userInfo
     * @param paginationParam
     * @param map
     * @return
     * @throws Exception
     */
    @RequestMapping("/warn")
    public String warn(UserInfo userInfo, @Valid PaginationParam paginationParam, Map<String, Object> map)
            throws Exception {
        setView(map, "warn", "我的预警");
        // 设置分页参数
        setPagination(map, paginationParam);
        // 获取警告数量
        long uid = userInfo.getUser().getId();
        Result<Integer> countResult = userWarnService.queryUserWarnCount(uid);
        if (!countResult.isOK()) {
            return view();
        }
        paginationParam.caculatePagination(countResult.getResult());
        // 获取警告列表
        Result<List<UserWarn>> result = userWarnService.queryUserWarnList(uid, paginationParam.getBegin(),
                paginationParam.getNumOfPage());
        setResult(map, result.getResult());
        return view();
    }
    
    /**
     * 用户警告详情
     * @param userInfo
     * @param paginationParam
     * @param map
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping("/warn/detail")
    public Result<UserWarn> warn(UserInfo userInfo, @RequestParam("wid") long wid, Map<String, Object> map)
            throws Exception {
        return userWarnService.queryWarnInfo(wid);
    }
    
    @ResponseBody
    @RequestMapping("/warn/count")
    public Result<List<UserWarnCount>> warn(UserInfo userInfo, @RequestParam("days") int days, Map<String, Object> map)
            throws Exception {
        return userWarnService.queryUserWarnCount(userInfo.getUser().getId(), days);
    }
    
    /**
     * 切换用户
     * 
     * @param userInfo
     * @param toUser
     * @param response
     * @return
     */
    @ResponseBody
    @RequestMapping("/switch")
    public Result<?> switchUser(UserInfo userInfo, @RequestParam("uid") long uid, HttpServletResponse response) {
        if (!userInfo.getUser().isAdmin()) {
            logger.warn("user:{} is not admin", userInfo.getUser().getEmail());
            return Result.getResult(Status.PERMISSION_DENIED_ERROR);
        }
        if (userInfo.getUser().getId() != uid) {
            Result<User> userResult = userService.query(uid);
            if (userResult.isOK()) {
                User user = userResult.getResult();
                // 设置到cookie中
                WebUtil.setLoginCookie(response, cipherHelper.encrypt(user.getEmail()));
                logger.info("{} switch to {}", userInfo.getUser().getEmail(), user.getEmail());
            }
        }
        return Result.getOKResult();
    }

    /**
     * 用户足迹
     */
    @RequestMapping("/footprint")
    public String footprint(UserInfo userInfo, @Valid PaginationParam paginationParam, Map<String, Object> map)
            throws Exception {
        setView(map, "footprint", "历史浏览");
        // 设置分页参数
        setPagination(map, paginationParam);
        long uid = userInfo.getUser().getId();
        Result<Integer> countResult = userFootprintService.queryCount(uid);
        if (!countResult.isOK()) {
            return view();
        }
        paginationParam.caculatePagination(countResult.getResult());
        // 获取列表
        Result<List<UserFootprint>> result = userFootprintService.queryByPage(uid, paginationParam.getBegin(),
                paginationParam.getNumOfPage());
        if (result.isEmpty()) {
            return view();
        }
        // 获取topic id列表
        List<UserFootprint> userFootprintList = result.getResult();
        List<FootprintVO> footprintVOList = new ArrayList<>();
        List<Long> idList = userFootprintList.stream().map(fp -> {
            footprintVOList.add(new FootprintVO(fp.getTid(), fp.getUpdateTime()));
            return fp.getTid();
        }).collect(Collectors.toList());
        Result<List<Topic>> topicListResult = topicService.queryTopicList(idList);
        if (topicListResult.isEmpty()) {
            return view();
        }
        // 转化为vo
        footprintVOList.forEach(fp -> {
            // 设置更新时间
            topicListResult.getResult().forEach(topic -> {
                if (topic.getId() == fp.getTid()) {
                    fp.setTopic(topic.getName());
                }
            });
        });
        setResult(map, footprintVOList);
        return view();
    }

    /**
     * 用户收藏
     */
    @RequestMapping("/favorite")
    public String favorite(UserInfo userInfo, @Valid PaginationParam paginationParam, Map<String, Object> map)
            throws Exception {
        // 设置返回视图
        setView(map, "favorite", "我的收藏");
        // 设置分页参数
        setPagination(map, paginationParam);
        long uid = userInfo.getUser().getId();
        Result<Integer> countResult = userFavoriteService.queryCount(uid);
        if (!countResult.isOK()) {
            return view();
        }
        paginationParam.caculatePagination(countResult.getResult());
        // 获取列表
        Result<List<UserFavorite>> result = userFavoriteService.queryByPage(uid, paginationParam.getBegin(),
                paginationParam.getNumOfPage());
        if (result.isEmpty()) {
            return view();
        }
        // 获取topic id列表
        List<UserFavorite> userFavoriteList = result.getResult();
        List<FavoriteVO> favoriteVOList = new ArrayList<>();
        List<Long> idList = userFavoriteList.stream().map(uf -> {
            favoriteVOList.add(new FavoriteVO(uf.getTid(), uf.getCreateTime()));
            return uf.getTid();
        }).collect(Collectors.toList());
        Result<List<Topic>> topicListResult = topicService.queryTopicList(idList);
        if (topicListResult.isEmpty()) {
            return view();
        }
        // 转化为vo
        favoriteVOList.forEach(ft -> {
            // 设置更新时间
            topicListResult.getResult().forEach(topic -> {
                if (topic.getId() == ft.getTid()) {
                    ft.setTopic(topic.getName());
                }
            });
        });
        setResult(map, favoriteVOList);
        return view();
    }

    /**
     * 收藏
     * @param userInfo
     * @param userParam
     * @param tid
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/favorite", method = RequestMethod.POST)
    public Result<?> favorite(UserInfo userInfo, @RequestParam("tid") long tid) throws Exception {
        logger.info("user:{}, favorite:{}", userInfo.getUser().getEmail(), tid);
        UserFavorite uf = new UserFavorite();
        uf.setUid(userInfo.getUser().getId());
        uf.setTid(tid);
        return Result.getWebResult(userFavoriteService.save(uf));
    }

    /**
     * 取消收藏
     * @param userInfo
     * @param userParam
     * @param tid
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/unfavorite", method = RequestMethod.POST)
    public Result<?> unfavorite(UserInfo userInfo, @RequestParam("tid") long tid) throws Exception {
        logger.info("user:{}, unfavorite:{}", userInfo.getUser().getEmail(), tid);
        Result<UserFavorite> result = userFavoriteService.query(userInfo.getUser().getId(), tid);
        if (result.isNotOK()) {
            return Result.getResult(Status.NO_RESULT);
        }
        return Result.getWebResult(userFavoriteService.delete(result.getResult().getId()));
    }
    
    @Override
    public String viewModule() {
        return "user";
    }
}
