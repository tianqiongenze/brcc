/*
 * Copyright (c) Baidu Inc. All rights reserved.
 * 
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.baidu.brcc.service.impl;

import static com.baidu.brcc.common.ErrorStatusMsg.ENVIRONMENT_ID_NOT_EXISTS_MSG;
import static com.baidu.brcc.common.ErrorStatusMsg.ENVIRONMENT_ID_NOT_EXISTS_STATUS;
import static com.baidu.brcc.common.ErrorStatusMsg.ENVIRONMENT_NAME_NOT_EMPTY_MSG;
import static com.baidu.brcc.common.ErrorStatusMsg.ENVIRONMENT_NAME_NOT_EMPTY_STATUS;
import static com.baidu.brcc.common.ErrorStatusMsg.ENVIRONMENT_NOT_EXISTS_MSG;
import static com.baidu.brcc.common.ErrorStatusMsg.ENVIRONMENT_NOT_EXISTS_STATUS;
import static com.baidu.brcc.common.ErrorStatusMsg.PRIV_MIS_MSG;
import static com.baidu.brcc.common.ErrorStatusMsg.PRIV_MIS_STATUS;
import static com.baidu.brcc.common.ErrorStatusMsg.VERSION_COPY_DEST_VERSION_NOT_EXISTS_MSG;
import static com.baidu.brcc.common.ErrorStatusMsg.VERSION_COPY_DEST_VERSION_NOT_EXISTS_STATUS;
import static com.baidu.brcc.common.ErrorStatusMsg.VERSION_COPY_SRC_VERSION_NOT_EXISTS_MSG;
import static com.baidu.brcc.common.ErrorStatusMsg.VERSION_COPY_SRC_VERSION_NOT_EXISTS_STATUS;
import static com.baidu.brcc.common.ErrorStatusMsg.VERSION_EXISTS_MSG;
import static com.baidu.brcc.common.ErrorStatusMsg.VERSION_EXISTS_STATUS;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import com.baidu.brcc.common.ErrorStatusMsg;
import com.baidu.brcc.dao.VersionMapper;
import com.baidu.brcc.dao.base.BaseMapper;
import com.baidu.brcc.domain.ConfigGroup;
import com.baidu.brcc.domain.ConfigGroupExample;
import com.baidu.brcc.domain.ConfigItem;
import com.baidu.brcc.domain.ConfigItemExample;
import com.baidu.brcc.domain.Environment;
import com.baidu.brcc.domain.Product;
import com.baidu.brcc.domain.Project;
import com.baidu.brcc.domain.User;
import com.baidu.brcc.domain.Version;
import com.baidu.brcc.domain.VersionExample;
import com.baidu.brcc.domain.em.Deleted;
import com.baidu.brcc.domain.exception.BizException;
import com.baidu.brcc.domain.meta.MetaEnvironment;
import com.baidu.brcc.domain.meta.MetaProduct;
import com.baidu.brcc.domain.meta.MetaProject;
import com.baidu.brcc.domain.meta.MetaVersion;
import com.baidu.brcc.domain.vo.ApiVersionVo;
import com.baidu.brcc.domain.vo.VersionNodeVo;
import com.baidu.brcc.service.ConfigGroupService;
import com.baidu.brcc.service.ConfigItemService;
import com.baidu.brcc.service.EnvironmentService;
import com.baidu.brcc.service.EnvironmentUserService;
import com.baidu.brcc.service.ProductService;
import com.baidu.brcc.service.ProjectService;
import com.baidu.brcc.service.ProjectUserService;
import com.baidu.brcc.service.RccCache;
import com.baidu.brcc.service.VersionService;
import com.baidu.brcc.service.base.GenericServiceImpl;
import com.baidu.brcc.utils.time.DateTimeUtils;

@Service("versionService")
public class VersionServiceImpl extends GenericServiceImpl<Version, Long, VersionExample> implements VersionService {

    @Autowired
    private VersionMapper versionMapper;

    @Autowired
    private ConfigGroupService configGroupService;

    @Autowired
    private ConfigItemService configItemService;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private EnvironmentService environmentService;

    @Autowired
    private EnvironmentUserService environmentUserService;

    @Autowired
    private ProjectUserService projectUserService;

    @Autowired
    private RccCache rccCache;

    @Override
    public BaseMapper<Version, Long, VersionExample> getMapper() {
        return versionMapper;
    }

    @Override
    public VersionExample newExample() {
        return VersionExample.newBuilder().build();
    }

    @Override
    public VersionExample newIdInExample(List<Long> ids) {
        return VersionExample.newBuilder().build().createCriteria().andIdIn(ids).toExample();
    }

    @Override
    @Transactional
    public int updateVersion(@NonNull Version version, String name, String memo, User loginUser) {
        Long versionId = version.getId();
        Version update = new Version();
        update.setId(versionId);
        update.setUpdateTime(new Date());
        if (StringUtils.isNotBlank(name)) {
            Version exists = selectOneByExample(VersionExample.newBuilder()
                            .build()
                            .createCriteria()
                            .andIdNotEqualTo(versionId)
                            .andNameEqualTo(name)
                            .andEnvironmentIdEqualTo(version.getEnvironmentId())
                            .toExample(),
                    MetaVersion.COLUMN_NAME_ID
            );
            if (exists != null) {
                throw new BizException(ErrorStatusMsg.PARAM_ERROR_STATUS, "版本已存在");
            }
            update.setName(name);
        }
        update.setMemo(memo);
        int cnt = updateByPrimaryKeySelective(update);

        version.getEnvironmentId();

        // 失效id->version的缓存
        rccCache.evictVersionById(Arrays.asList(versionId));

        return cnt;
    }

    @Override
    @Transactional
    public Long saveVersion(Long environmentId, String name, String memo, User loginUser) {
        // 新增
        if (null == environmentId || environmentId <= 0) {
            throw new BizException(ENVIRONMENT_ID_NOT_EXISTS_STATUS, ENVIRONMENT_ID_NOT_EXISTS_MSG);
        }
        if (StringUtils.isBlank(name)) {
            throw new BizException(ENVIRONMENT_NAME_NOT_EMPTY_STATUS, ENVIRONMENT_NAME_NOT_EMPTY_MSG);
        }
        Environment environment = environmentService.selectByPrimaryKey(environmentId);
        if (environment == null || Deleted.DELETE.getValue().equals(environment.getDeleted())) {
            throw new BizException(ENVIRONMENT_NOT_EXISTS_STATUS, ENVIRONMENT_NOT_EXISTS_MSG);
        }
        if (!projectUserService.checkAuth(environment.getProductId(), environment.getProjectId(), loginUser)) {
            throw new BizException(PRIV_MIS_STATUS, PRIV_MIS_MSG);
        }
        Version version = selectOneByExample(VersionExample.newBuilder()
                        .build()
                        .createCriteria()
                        .andDeletedEqualTo(Deleted.OK.getValue())
                        .andEnvironmentIdEqualTo(environmentId)
                        .andNameEqualTo(name)
                        .toExample(),
                MetaVersion.COLUMN_NAME_ID
        );
        if (version != null) {
            throw new BizException(VERSION_EXISTS_STATUS, VERSION_EXISTS_MSG);
        }

        Version insert = new Version();
        insert.setUpdateTime(new Date());
        insert.setCreateTime(new Date());
        insert.setDeleted(Deleted.OK.getValue());
        insert.setName(name);
        insert.setMemo(memo);
        insert.setEnvironmentId(environmentId);
        insert.setProjectId(environment.getProjectId());
        insert.setProductId(environment.getProductId());
        insert.setCheckSum(UUID.randomUUID().toString());
        insert.setCheckSumDate(new Date());
        insertSelective(insert);
        return insert.getId();
    }

    @Override
    public Integer deleteCascadeByVersionId(Long versionId) {

        Date now = DateTimeUtils.now();
        Version version = selectByPrimaryKey(versionId);

        if (version == null) {
            return 0;
        }

        // 删除版本
        int del = updateByPrimaryKeySelective(Version.newBuilder()
                .id(versionId)
                .deleted(Deleted.DELETE.getValue())
                .updateTime(now)
                .build());

        // 删除分组
        configGroupService.deleteByVersionId(versionId);

        // 删除配置项
        configItemService.deleteByVersionId(versionId);

        // 失效缓存
        rccCache.deleteVersionCascade(version);

        return del;
    }

    @Override
    public int deleteByEnvId(Long envId) {
        return updateByExampleSelective(
                Version.newBuilder()
                        .deleted(Deleted.DELETE.getValue())
                        .updateTime(DateTimeUtils.now())
                        .build(),
                VersionExample.newBuilder()
                        .build()
                        .createCriteria()
                        .andEnvironmentIdEqualTo(envId)
                        .andDeletedEqualTo(Deleted.DELETE.getValue())
                        .toExample());
    }

    @Override
    public int deleteByProjectId(Long projectId) {
        return updateByExampleSelective(
                Version.newBuilder()
                        .deleted(Deleted.DELETE.getValue())
                        .updateTime(DateTimeUtils.now())
                        .build(),
                VersionExample.newBuilder()
                        .build()
                        .createCriteria()
                        .andProjectIdEqualTo(projectId)
                        .andDeletedEqualTo(Deleted.DELETE.getValue())
                        .toExample());
    }

    @Transactional
    @Override
    public void copyConfigItemsFromVersion(Long srcVerId, Long destVerId) {

        Version destVersion = selectByPrimaryKey(destVerId);
        if (destVersion == null) {
            throw new BizException(VERSION_COPY_DEST_VERSION_NOT_EXISTS_STATUS,
                    VERSION_COPY_DEST_VERSION_NOT_EXISTS_MSG);
        }
        Version srcVersion = selectByPrimaryKey(srcVerId);
        if (srcVersion == null) {
            throw new BizException(VERSION_COPY_SRC_VERSION_NOT_EXISTS_STATUS, VERSION_COPY_SRC_VERSION_NOT_EXISTS_MSG);
        }

        // 根据源versionId查找所有group，使用新增的versionId,复制一份
        List<ConfigGroup> configGroupList = configGroupService.selectByExample(
                ConfigGroupExample.newBuilder()
                        .build()
                        .createCriteria()
                        .andVersionIdEqualTo(srcVerId)
                        .andDeletedEqualTo(Deleted.OK.getValue())
                        .toExample());

        Date now = new Date();
        for (ConfigGroup configGroup : configGroupList) {
            Long srcGroupId = configGroup.getId();
            configGroup.setId(null);
            configGroup.setMemo("");
            configGroup.setCreateTime(now);
            configGroup.setUpdateTime(now);
            configGroup.setVersionId(destVerId);
            configGroup.setDeleted(Deleted.OK.getValue());
            configGroup.setEnvironmentId(destVersion.getEnvironmentId());
            configGroup.setProjectId(destVersion.getProjectId());
            configGroup.setProductId(destVersion.getProductId());
            configGroupService.insertSelective(configGroup);
            configGroup.setId(configGroup.getId());

            // 从一个分组复制到另一个分组
            copyConfigItemsFromGroup(srcGroupId, configGroup);
        }

    }

    @Transactional
    @Override
    public void copyConfigItemsFromGroup(Long srcGroupId, ConfigGroup destGroup) {
        Date now = new Date();

        List<ConfigItem> srcConfigItems = configItemService.selectByExample(ConfigItemExample.newBuilder()
                .build()
                .createCriteria()
                .andGroupIdEqualTo(srcGroupId)
                .andDeletedEqualTo(Deleted.OK.getValue())
                .toExample());
        for (ConfigItem srcConfigItem : srcConfigItems) {
            srcConfigItem.setId(null);
            srcConfigItem.setMemo("");
            srcConfigItem.setGroupId(destGroup.getId());
            srcConfigItem.setCreateTime(now);
            srcConfigItem.setUpdateTime(now);
            srcConfigItem.setVersionId(destGroup.getVersionId());
            srcConfigItem.setGroupId(destGroup.getId());
            srcConfigItem.setVersionId(destGroup.getVersionId());
            srcConfigItem.setEnvironmentId(destGroup.getEnvironmentId());
            srcConfigItem.setProjectId(destGroup.getProjectId());
            srcConfigItem.setProductId(destGroup.getProductId());
        }
        configItemService.saveConfigItems(srcConfigItems, null, null);

    }

    @Override
    public List<VersionNodeVo> myAllVersion(User user, Long productId, Long projectId) {
        Map<Long, Product> productManageMap = new HashMap<>();
        Map<Long, Project> projectManageMap = new HashMap<>();
        Map<Long, Environment> envAccessMap = new HashMap<>();
        Map<Long, Version> versionAccessMap = new HashMap<>();
        Map<Long, ConfigGroup> groupAccessMap = new HashMap<>();

        configGroupService.loadGroupByUser(
                user,
                ConfigGroupService.VERSION,
                productManageMap,
                projectManageMap,
                envAccessMap,
                versionAccessMap,
                groupAccessMap
        );

        List<VersionNodeVo> versionNodeVos = new ArrayList<>();
        if (!CollectionUtils.isEmpty(versionAccessMap)) {
            Set<Long> lostEnvironmentIds = new HashSet<>();
            Set<Long> lostProjectIds = new HashSet<>();
            Set<Long> lostProductIds = new HashSet<>();
            for (Version version : versionAccessMap.values()) {
                if (productId != null && productId > 0 && !productId.equals(version.getProductId())) {
                    continue;
                }
                if (projectId != null && projectId > 0 && !projectId.equals(version.getProjectId())) {
                    continue;
                }
                VersionNodeVo vo = new VersionNodeVo();
                vo.setVersionId(version.getId());
                vo.setVersionName(version.getName());
                vo.setEnvironmentId(version.getEnvironmentId());
                vo.setProjectId(version.getProjectId());
                vo.setProductId(version.getProductId());
                versionNodeVos.add(vo);

                if (!envAccessMap.containsKey(version.getEnvironmentId())) {
                    lostEnvironmentIds.add(version.getEnvironmentId());
                }

                if (!projectManageMap.containsKey(version.getProjectId())) {
                    lostProjectIds.add(version.getProjectId());
                }

                if (!productManageMap.containsKey(version.getProductId())) {
                    lostProductIds.add(version.getProductId());
                }
            }

            if (!CollectionUtils.isEmpty(lostProductIds)) {
                List<Product> products = productService.selectByPrimaryKeys(
                        lostProductIds,
                        MetaProduct.COLUMN_NAME_ID,
                        MetaProduct.COLUMN_NAME_NAME
                );

                if (!CollectionUtils.isEmpty(products)) {
                    for (Product product : products) {
                        productManageMap.put(product.getId(), product);
                    }
                }
            }

            if (!CollectionUtils.isEmpty(lostProjectIds)) {
                List<Project> projects = projectService.selectByPrimaryKeys(
                        lostProjectIds,
                        MetaProject.COLUMN_NAME_ID,
                        MetaProject.COLUMN_NAME_NAME,
                        MetaProject.COLUMN_NAME_PRODUCTID
                );

                if (!CollectionUtils.isEmpty(projects)) {
                    for (Project project : projects) {
                        projectManageMap.put(project.getId(), project);
                    }
                }
            }

            if (!CollectionUtils.isEmpty(lostEnvironmentIds)) {
                List<Environment> environments = environmentService.selectByPrimaryKeys(
                        lostEnvironmentIds,
                        MetaEnvironment.COLUMN_NAME_ID,
                        MetaEnvironment.COLUMN_NAME_NAME,
                        MetaEnvironment.COLUMN_NAME_PROJECTID,
                        MetaEnvironment.COLUMN_NAME_PRODUCTID
                );

                if (!CollectionUtils.isEmpty(environments)) {
                    for (Environment environment : environments) {
                        envAccessMap.put(environment.getId(), environment);
                    }
                }
            }

            for (VersionNodeVo vo : versionNodeVos) {
                Environment environment = envAccessMap.get(vo.getEnvironmentId());
                if (environment != null) {
                    vo.setEnvironmentName(environment.getName());
                }
                Project project = projectManageMap.get(vo.getProjectId());
                if (project != null) {
                    vo.setProjectName(project.getName());
                }
                Product product = productManageMap.get(vo.getProductId());
                if (product != null) {
                    vo.setProductName(product.getName());
                }
            }
        }
        return versionNodeVos;
    }

    @Override
    public Boolean checkAuth(User user, Long srcVerId, Long destVerId) {

        List<Version> versions = this.selectByExample(VersionExample.newBuilder()
                .build()
                .createCriteria()
                .andIdIn(new ArrayList<Long>() {{
                    add(srcVerId);
                    add(destVerId);
                }})
                .toExample());
        if (CollectionUtils.isEmpty(versions) || versions.size() != 2) {

            return false;
        }

        boolean checkRet = false;
        for (Version version : versions) {
            checkRet = environmentUserService.checkAuth(version.getProductId(),
                    version.getProjectId(),
                    version.getEnvironmentId(),
                    user);
        }

        return checkRet;

    }

    @Override
    public Version selectByProjectIdAndEnvironmentIdAndName(Long projectId, Long environmentId, String name) {
        return selectOneByExample(VersionExample.newBuilder()
                .build()
                .createCriteria()
                .andDeletedEqualTo(Deleted.OK.getValue())
                .andProjectIdEqualTo(projectId)
                .andEnvironmentIdEqualTo(environmentId)
                .andNameEqualTo(name)
                .toExample()
        );
    }

    @Override
    public List<Version> selectByProjectIdAndEnvironment(Long projectId, Long environmentId) {
        return selectByExample(VersionExample.newBuilder()
                .build()
                .createCriteria()
                .andDeletedEqualTo(Deleted.OK.getValue())
                .andProjectIdEqualTo(projectId)
                .andEnvironmentIdEqualTo(environmentId)
                .toExample()
        );
    }

    @Override
    public List<Long> selectIdsByEnvironmentIds(Long projectId, List<Long> environmentIds) {
        if (projectId == null || projectId <= 0 || CollectionUtils.isEmpty(environmentIds)) {
            return null;
        }
        return selectByExample(VersionExample.newBuilder()
                        .build()
                        .createCriteria()
                        .andDeletedEqualTo(Deleted.OK.getValue())
                        .andProjectIdEqualTo(projectId)
                        .andEnvironmentIdIn(environmentIds)
                        .toExample(),
                Version :: getId,
                MetaVersion.COLUMN_NAME_ID
        );
    }

    @Override
    public ApiVersionVo getByEnvironmentByIdInCache(Long versionId) {
        ApiVersionVo vo = rccCache.getVersionById(versionId);
        if (vo == null) {
            Version v = selectByPrimaryKey(versionId);
            if (v != null) {
                vo = new ApiVersionVo().copyFrom(v);
                rccCache.loadVersionForId(vo);
            }
        }
        return vo;
    }

    @Override
    public ApiVersionVo getByEnvironmentAndNameInCache(Long projectId, Long environmentId, String name) {
        ApiVersionVo versionVo = rccCache.getVersion(environmentId, name);
        if (versionVo == null) {
            // 缓存可用且版本的HKEY不存在，加载所有版本
            if (rccCache.cacheEnable() && !rccCache.existsVersionHKey(environmentId)) {
                List<Version> versions = selectByProjectIdAndEnvironment(
                        projectId,
                        environmentId
                );
                if (!CollectionUtils.isEmpty(versions)) {
                    List<ApiVersionVo> versionVos = new ArrayList<>(versions.size());
                    for (Version version : versions) {
                        ApiVersionVo vo = new ApiVersionVo().copyFrom(version);
                        versionVos.add(vo);
                        if (StringUtils.equals(name, version.getName())) {
                            versionVo = vo;
                        }
                    }
                    rccCache.loadVersions(environmentId, versionVos);
                }
            } else {
                Version version = selectByProjectIdAndEnvironmentIdAndName(
                        projectId,
                        environmentId,
                        name);
                if (version != null) {
                    versionVo = new ApiVersionVo().copyFrom(version);
                    // 为了数据一致性，此处不能重载缓存
                    // rccCache.loadVersion(versionVo);
                }
            }
        }
        return versionVo;
    }

    @Override
    public List<ApiVersionVo> getAllByEnvironmentIdInCache(Long projectId, Long environmentId) {
        List<ApiVersionVo> versionVos = rccCache.getVersions(environmentId);
        if (CollectionUtils.isEmpty(versionVos)) {
            List<Version> versions = selectByProjectIdAndEnvironment(
                    projectId,
                    environmentId
            );
            if (!CollectionUtils.isEmpty(versions)) {
                versionVos = new ArrayList<>(versions.size());
                for (Version version : versions) {
                    ApiVersionVo vo = new ApiVersionVo();
                    vo.setProjectId(version.getProjectId());
                    vo.setEnvironmentId(version.getEnvironmentId());
                    vo.setVersionId(version.getId());
                    vo.setVersionName(version.getName());
                    vo.setCheckSum(version.getCheckSum());
                    versionVos.add(vo);
                }
                rccCache.loadVersions(environmentId, versionVos);
            }
        }
        return versionVos;
    }
}
