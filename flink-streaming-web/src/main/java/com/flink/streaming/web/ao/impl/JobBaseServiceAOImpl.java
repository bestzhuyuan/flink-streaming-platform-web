package com.flink.streaming.web.ao.impl;

import cn.hutool.core.date.DateUtil;

import com.alibaba.nacos.api.annotation.NacosInjected;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.annotation.NacosValue;
import com.flink.streaming.common.constant.SystemConstant;
import com.flink.streaming.common.enums.JobTypeEnum;
import com.flink.streaming.web.ao.JobBaseServiceAO;
import com.flink.streaming.web.common.MessageConstants;
import com.flink.streaming.web.common.RestResult;
import com.flink.streaming.web.common.SystemConstants;
import com.flink.streaming.web.common.TipsConstants;
import com.flink.streaming.web.common.util.*;
import com.flink.streaming.web.config.JobThreadPool;
import com.flink.streaming.web.enums.*;
import com.flink.streaming.web.exceptions.BizException;
import com.flink.streaming.web.model.dto.JobConfigDTO;
import com.flink.streaming.web.model.dto.JobRunLogDTO;
import com.flink.streaming.web.model.dto.JobRunParamDTO;
import com.flink.streaming.web.model.dto.SystemConfigDTO;
import com.flink.streaming.web.rpc.CommandRpcClinetAdapter;
import com.flink.streaming.web.rpc.FlinkRestRpcAdapter;
import com.flink.streaming.web.rpc.YarnRestRpcAdapter;
import com.flink.streaming.web.rpc.model.JobStandaloneInfo;
import com.flink.streaming.web.service.JobConfigService;
import com.flink.streaming.web.service.JobRunLogService;
import com.flink.streaming.web.service.SystemConfigService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author zhuhuipei
 * @Description:
 * @date 2021/3/28
 * @time 10:02
 */
@Component
@Slf4j
public class JobBaseServiceAOImpl implements JobBaseServiceAO {

    public static final ThreadLocal<String> threadAppId = new ThreadLocal<String>();

    @Autowired
    private JobRunLogService jobRunLogService;


    @Autowired
    private YarnRestRpcAdapter yarnRestRpcAdapter;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private CommandRpcClinetAdapter commandRpcClinetAdapter;


    @Autowired
    private JobConfigService jobConfigService;

    @Autowired
    private FlinkRestRpcAdapter flinkRestRpcAdapter;

    @NacosInjected
    private ConfigService configService;

    @NacosValue(value = "${nacos.config.data-id:}", autoRefreshed = true)
    private String nacosConfigDataId;

    @NacosValue(value = "${nacos.config.group:}", autoRefreshed = true)
    private String nacosConfigGroup;

    @Override
    public void checkStart(JobConfigDTO jobConfigDTO) {
        if (jobConfigDTO == null) {
            throw new BizException(SysErrorEnum.JOB_CONFIG_JOB_IS_NOT_EXIST);
        }
        if (JobConfigStatus.RUN.getCode().equals(jobConfigDTO.getStatus().getCode())) {
            throw new BizException("?????????????????????????????????");
        }
        if (jobConfigDTO.getStatus().equals(JobConfigStatus.STARTING)) {
            throw new BizException("????????????????????? ?????????..");
        }
        if (jobConfigDTO.getIsOpen().intValue() == YN.N.getValue()) {
            throw new BizException("??????????????????");
        }
        switch (jobConfigDTO.getDeployModeEnum()) {
            case YARN_PER:
                this.checYarnQueue(jobConfigDTO);
                RestResult restResult = CliConfigUtil.checkFlinkRunConfigForYarn(jobConfigDTO.getFlinkRunConfig());
                if (restResult != null && !restResult.isSuccess()) {
                    throw new BizException("?????????????????????????????????" + restResult.getMessage());
                }
                break;
        }

        Map<String, String> systemConfigMap = SystemConfigDTO.toMap(systemConfigService.getSystemConfig(SysConfigEnumType.SYS));
        this.checkSysConfig(systemConfigMap, jobConfigDTO.getDeployModeEnum());


    }

    @Override
    public void checkSavepoint(JobConfigDTO jobConfigDTO) {
        if (jobConfigDTO == null) {
            throw new BizException(SysErrorEnum.JOB_CONFIG_JOB_IS_NOT_EXIST);
        }
//        if (JobTypeEnum.JAR.equals(jobConfigDTO.getJobTypeEnum())) {
//            log.warn(MessageConstants.MESSAGE_006, jobConfigDTO.getJobName());
//            throw new BizException(MessageConstants.MESSAGE_006);
//        }
        if (JobTypeEnum.SQL_BATCH.equals(jobConfigDTO.getJobTypeEnum())) {
            log.warn(MessageConstants.MESSAGE_010, jobConfigDTO.getJobName());
            throw new BizException(MessageConstants.MESSAGE_010);
        }

        if (StringUtils.isEmpty(jobConfigDTO.getFlinkCheckpointConfig()) &&
                DeployModeEnum.STANDALONE.equals(jobConfigDTO.getDeployModeEnum())) {
            log.error(MessageConstants.MESSAGE_004, jobConfigDTO);
            throw new BizException(MessageConstants.MESSAGE_004);
        }
        if (StringUtils.isEmpty(jobConfigDTO.getJobId())) {
            log.warn(MessageConstants.MESSAGE_005, jobConfigDTO.getJobName());
            throw new BizException(MessageConstants.MESSAGE_005);
        }


    }

    @Override
    public void checkClose(JobConfigDTO jobConfigDTO) {

        if (jobConfigDTO.getStatus().equals(JobConfigStatus.RUN)) {
            throw new BizException(MessageConstants.MESSAGE_002);
        }
        if (jobConfigDTO.getStatus().equals(JobConfigStatus.STARTING)) {
            throw new BizException(MessageConstants.MESSAGE_003);
        }
    }

    @Override
    public Long insertJobRunLog(JobConfigDTO jobConfigDTO, String userName) {
        JobRunLogDTO jobRunLogDTO = new JobRunLogDTO();
        jobRunLogDTO.setDeployMode(jobConfigDTO.getDeployModeEnum().name());
        jobRunLogDTO.setLocalLog(MessageConstants.MESSAGE_001);
        jobRunLogDTO.setJobConfigId(jobConfigDTO.getId());
        jobRunLogDTO.setStartTime(new Date());
        jobRunLogDTO.setJobName(jobConfigDTO.getJobName());
        jobRunLogDTO.setJobId(jobConfigDTO.getJobId());
        jobRunLogDTO.setJobStatus(JobStatusEnum.STARTING.name());
        jobRunLogDTO.setCreator(userName);
        jobRunLogDTO.setEditor(userName);
        jobRunLogDTO.setRunIp(IpUtil.getInstance().getLocalIP());
        return jobRunLogService.insertJobRunLog(jobRunLogDTO);
    }

    @Override
    public JobRunParamDTO writeSqlToFile(JobConfigDTO jobConfigDTO) {
        if (configService != null && StringUtils.isNotEmpty(nacosConfigDataId)
            && StringUtils.isNotEmpty(nacosConfigGroup)) {
            replaceNacosParameters(jobConfigDTO);
        }
        Map<String, String> systemConfigMap = SystemConfigDTO.toMap(systemConfigService.getSystemConfig(SysConfigEnumType.SYS));

        String sqlPath = FileUtils.getSqlHome(systemConfigMap.get(SysConfigEnum.FLINK_STREAMING_PLATFORM_WEB_HOME.getKey()))
                + FileUtils.createFileName(String.valueOf(jobConfigDTO.getId()));
        FileUtils.writeText(sqlPath, jobConfigDTO.getFlinkSql(), Boolean.FALSE);

        return JobRunParamDTO.buildJobRunParam(systemConfigMap, jobConfigDTO, sqlPath);
    }

    @Override
    public void aSyncExecJob(JobRunParamDTO jobRunParamDTO, JobConfigDTO jobConfigDTO,
                             Long jobRunLogId, String savepointPath) {
        if (configService != null && StringUtils.isNotEmpty(nacosConfigDataId)
            && StringUtils.isNotEmpty(nacosConfigGroup)) {
            replaceNacosParameters(jobConfigDTO);
        }
        ThreadPoolExecutor threadPoolExecutor = JobThreadPool.getInstance().getThreadPoolExecutor();
        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                String jobStatus = JobStatusEnum.SUCCESS.name();
                String appId = "";
                threadAppId.set(appId);
                boolean success = true;
                StringBuilder localLog = new StringBuilder()
                        .append("?????????????????????")
                        .append(DateUtil.now()).append(SystemConstant.LINE_FEED)
                        .append("??????jar: ").append(SystemConstant.LINE_FEED);
                         if(jobConfigDTO.getExtJarPath()!=null){
                             localLog.append(jobConfigDTO.getExtJarPath());
                         }

                localLog.append(SystemConstant.LINE_FEED)
                        .append("?????????IP???").append(IpUtil.getInstance().getLocalIP())
                        .append(SystemConstant.LINE_FEED);
                try {
                    String command = "";

                    //????????????????????????jar???????????????????????????
                    this.downJar(jobRunParamDTO, jobConfigDTO);
                    switch (jobConfigDTO.getDeployModeEnum()) {
                        case YARN_PER:
                            //localLog.append("HADOOP_CLASSPATH=").append(System.getProperty("HADOOP_CLASSPATH")).append(SystemConstant.LINE_FEED);
                            //1?????????????????????
                            command = CommandUtil.buildRunCommandForYarnCluster(jobRunParamDTO,
                                    jobConfigDTO, savepointPath);
                            //2???????????????
                            appId = this.submitJobForYarn(command, jobConfigDTO, localLog);
                            break;
                        case LOCAL:
                        case STANDALONE:
                            //1?????????????????????
                            command = CommandUtil.buildRunCommandForCluster(jobRunParamDTO, jobConfigDTO, savepointPath);
                            //2???????????????
                            appId = this.submitJobForStandalone(command, jobConfigDTO, localLog);
                            break;
                    }
                } catch (Exception e) {
                    log.error("??????[" + jobConfigDTO.getId() + "]??????????????????", e);
                    localLog.append(e).append(errorInfoDir());
                    success = false;
                    jobStatus = JobStatusEnum.FAIL.name();
                } finally {
                    localLog.append("\n??????????????????: ").append(DateUtil.now()).append(SystemConstant.LINE_FEED);
                    if (success) {
                        localLog.append("######??????????????? ??????############################## ");
                    } else {
                        localLog.append("######??????????????? ??????############################## ");
                    }
                    if (StringUtils.isBlank(appId)) { // ???????????????????????????????????????appID????????????????????????????????????????????????
                        appId = threadAppId.get();
                        log.info("??????[{}]????????????????????????appid = {}", jobConfigDTO.getId(), appId);
                    }
                    this.updateStatusAndLog(jobConfigDTO, jobRunLogId, jobStatus, localLog.toString(), appId);
                }
            }


            /**
             *???????????????????????????setMainJarPath
             * @author zhuhuipei
             * @date 2021/3/28
             * @time 14:58
             */
            private void downJar(JobRunParamDTO jobRunParamDTO, JobConfigDTO jobConfigDTO) {
                if (JobTypeEnum.JAR.getCode() == jobConfigDTO.getJobTypeEnum().getCode()) {
                    String savePath = jobRunParamDTO.getSysHome() + "tmp/udf_jar/";
                    try {
                        String pathName = UrlUtil.downLoadFromUrl(jobConfigDTO.getCustomJarUrl(), savePath);
                        jobRunParamDTO.setMainJarPath(pathName);
                    } catch (Exception e) {
                        log.error("?????????????????? {}", jobConfigDTO.getCustomJarUrl(), e);
                        throw new BizException("??????????????????");
                    }
                }
            }

            /**
             *????????????????????????
             * @author zhuhuipei
             * @date 2020-10-19
             * @time 21:47
             */
            private String errorInfoDir() {
                StringBuilder errorTips = new StringBuilder(SystemConstant.LINE_FEED)
                        .append(SystemConstant.LINE_FEED)
                        .append("??????????????????????????????????????????????????????????????????????????????")
                        .append(IpUtil.getInstance().getLocalIP()).append(SystemConstant.LINE_FEED)
                        .append("web?????????????????????web????????????")
                        .append(systemConfigService
                                .getSystemConfigByKey(SysConfigEnum.FLINK_STREAMING_PLATFORM_WEB_HOME.getKey()))
                        .append("logs/error.log").append(SystemConstant.LINE_FEED)
                        .append("flink?????????????????????flink??????????????????")
                        .append(systemConfigService.getSystemConfigByKey(SysConfigEnum.FLINK_HOME.getKey()))
                        .append("log/)").append(SystemConstant.LINE_FEED)
                        .append(SystemConstant.LINE_FEED)
                        .append(SystemConstant.LINE_FEED);
                return errorTips.toString();
            }


            /**
             * ?????????????????????????????????
             * @param jobConfig
             * @param jobRunLogId
             * @param jobStatus
             * @param localLog
             * @param appId
             */
            private void updateStatusAndLog(JobConfigDTO jobConfig, Long jobRunLogId,
                                            String jobStatus, String localLog, String appId) {
                try {
                    JobConfigDTO jobConfigDTO = new JobConfigDTO();
                    jobConfigDTO.setId(jobConfig.getId());
                    jobConfigDTO.setJobId(appId);
                    JobRunLogDTO jobRunLogDTO = new JobRunLogDTO();
                    jobRunLogDTO.setId(jobRunLogId);
                    jobRunLogDTO.setJobId(appId);
                    if (JobStatusEnum.SUCCESS.name().equals(jobStatus) && !StringUtils.isEmpty(appId)) {

                        //?????????????????????????????????
                        if (JobTypeEnum.SQL_BATCH.equals(jobConfig.getJobTypeEnum())) {
                            jobConfigDTO.setStatus(JobConfigStatus.SUCCESS);
                        } else {
                            jobConfigDTO.setStatus(JobConfigStatus.RUN);
                        }

                        jobConfigDTO.setLastStartTime(new Date());
                        if (DeployModeEnum.LOCAL.equals(jobConfig.getDeployModeEnum()) ||
                                DeployModeEnum.STANDALONE.equals(jobConfig.getDeployModeEnum())) {
                            jobRunLogDTO.setRemoteLogUrl(systemConfigService.getFlinkHttpAddress(jobConfig.getDeployModeEnum())
                                    + SystemConstants.HTTP_STANDALONE_APPS + jobConfigDTO.getJobId());
                        }
                        if (DeployModeEnum.YARN_PER.equals(jobConfig.getDeployModeEnum())) {
                            jobRunLogDTO.setRemoteLogUrl(systemConfigService.getYarnRmHttpAddress()
                                    + SystemConstants.HTTP_YARN_CLUSTER_APPS + jobConfigDTO.getJobId());
                        }

                    } else {
                        jobConfigDTO.setStatus(JobConfigStatus.FAIL);
                    }
                    jobConfigService.updateJobConfigById(jobConfigDTO);

                    jobRunLogDTO.setJobStatus(jobStatus);
                    jobRunLogDTO.setLocalLog(localLog);
                    jobRunLogService.updateJobRunLogById(jobRunLogDTO);

                    //???????????????????????? (????????????????????????????????? ?????????????????????????????????????????????????????????)
                    jobRunLogService.updateLogById(localLog, jobRunLogId);
                } catch (Exception e) {
                    log.error(" localLog.length={} ???????????????????????????", localLog.length(), e);
                }
            }

            private String submitJobForStandalone(String command, JobConfigDTO jobConfig, StringBuilder localLog)
                    throws Exception {
                String appId = commandRpcClinetAdapter.submitJob(command, localLog, jobRunLogId, jobConfig.getDeployModeEnum());
                JobStandaloneInfo jobStandaloneInfo = flinkRestRpcAdapter.getJobInfoForStandaloneByAppId(appId,
                        jobConfig.getDeployModeEnum());

                if (jobStandaloneInfo == null || StringUtils.isNotEmpty(jobStandaloneInfo.getErrors())) {
                    log.error("[submitJobForStandalone] is error jobStandaloneInfo={}", jobStandaloneInfo);
                    localLog.append("\n ???????????? appId=" + appId);
                    throw new BizException("????????????");
                } else {
                    if (!SystemConstants.STATUS_RUNNING.equals(jobStandaloneInfo.getState())) {
                        localLog.append("\n ???????????? appId=" + appId).append("????????????" + jobStandaloneInfo.getState());
                        throw new BizException("[submitJobForStandalone]????????????");
                    }
                }
                return appId;
            }

            private String submitJobForYarn(String command, JobConfigDTO jobConfigDTO, StringBuilder localLog)
                    throws Exception {
                commandRpcClinetAdapter.submitJob(command, localLog, jobRunLogId, jobConfigDTO.getDeployModeEnum());
                return yarnRestRpcAdapter.getAppIdByYarn(jobConfigDTO.getJobName(),
                        YarnUtil.getQueueName(jobConfigDTO.getFlinkRunConfig()));
            }

        });
    }

    private void checYarnQueue(JobConfigDTO jobConfigDTO) {
        try {
            String queueName = YarnUtil.getQueueName(jobConfigDTO.getFlinkRunConfig());
            if (StringUtils.isEmpty(queueName)) {
                throw new BizException("?????????????????????????????????????????? flink??????????????????");
            }
            String appId = yarnRestRpcAdapter.getAppIdByYarn(jobConfigDTO.getJobName(), queueName);
            if (StringUtils.isNotEmpty(appId)) {
                throw new BizException("????????????yarn?????????????????????????????????????????????????????? ???????????????:" +
                        jobConfigDTO.getJobName() + " ???????????????:" + queueName + TipsConstants.TIPS_1);
            }
        } catch (BizException e) {
            if (e != null && SysErrorEnum.YARN_CODE.getCode().equals(e.getCode())) {
                log.info(e.getErrorMsg());
            } else {
                throw e;
            }
        } catch (Exception e) {
            throw new BizException(e.getMessage());
        }
    }

    private void checkSysConfig(Map<String, String> systemConfigMap, DeployModeEnum deployModeEnum) {
        if (systemConfigMap == null) {
            throw new BizException(SysErrorEnum.SYSTEM_CONFIG_IS_NULL);
        }
        if (!systemConfigMap.containsKey(SysConfigEnum.FLINK_HOME.getKey())) {
            throw new BizException(SysErrorEnum.SYSTEM_CONFIG_IS_NULL_FLINK_HOME);
        }

        if (DeployModeEnum.YARN_PER.equals(deployModeEnum)) {
            if (!systemConfigMap.containsKey(SysConfigEnum.YARN_RM_HTTP_ADDRESS.getKey())) {
                throw new BizException(SysErrorEnum.SYSTEM_CONFIG_IS_NULL_YARN_RM_HTTP_ADDRESS);
            }
        }
        if (!systemConfigMap.containsKey(SysConfigEnum.FLINK_STREAMING_PLATFORM_WEB_HOME.getKey())) {
            throw new BizException(SysErrorEnum.SYSTEM_CONFIG_IS_NULL_FLINK_STREAMING_PLATFORM_WEB_HOME);
        }
    }

    /**
     * ?????????????????????????????????${xxxx}
     */
    private static Pattern param_pattern = Pattern.compile("\\$\\{[\\w.-]+\\}");

    /**
     * ??????Nacos??????????????????????????????
     *
     * @param jobConfigDTO
     * @author wxj
     * @date 2021???12???29??? ??????3:02:46
     * @version V1.0
     */
    private void replaceNacosParameters(JobConfigDTO jobConfigDTO) {
        try {
            String configInfo = configService.getConfig(nacosConfigDataId, nacosConfigGroup, 3000);
            Properties properties = new Properties();
            properties.load(new StringReader(configInfo));
            jobConfigDTO.setFlinkRunConfig(replaceParamter(properties, jobConfigDTO.getFlinkRunConfig()));
            jobConfigDTO.setFlinkCheckpointConfig(replaceParamter(properties, jobConfigDTO.getFlinkCheckpointConfig()));
            jobConfigDTO.setExtJarPath(replaceParamter(properties, jobConfigDTO.getExtJarPath()));
            jobConfigDTO.setFlinkSql(replaceParamter(properties, jobConfigDTO.getFlinkSql()));
            jobConfigDTO.setCustomArgs(replaceParamter(properties, jobConfigDTO.getCustomArgs()));
            jobConfigDTO.setCustomMainClass(replaceParamter(properties, jobConfigDTO.getCustomMainClass()));
            jobConfigDTO.setCustomJarUrl(replaceParamter(properties, jobConfigDTO.getCustomJarUrl()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String replaceParamter(Properties properties, String text) {
        if (text == null) {
            return null;
        }
        Matcher m = param_pattern.matcher(text);
        while (m.find()) {
            String param = m.group();
            String key = param.substring(2, param.length() - 1);
            String value = (String)properties.get(key);
            if (value != null) {
                text = text.replace(param, value);
            }
        }
        return text;
    }
}
