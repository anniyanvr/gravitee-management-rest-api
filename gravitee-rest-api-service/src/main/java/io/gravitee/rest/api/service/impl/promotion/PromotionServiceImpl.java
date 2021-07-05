/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.rest.api.service.impl.promotion;

import io.gravitee.common.data.domain.Page;
import io.gravitee.repository.exceptions.TechnicalException;
import io.gravitee.repository.management.api.PromotionRepository;
import io.gravitee.repository.management.api.search.Order;
import io.gravitee.repository.management.api.search.PromotionCriteria;
import io.gravitee.repository.management.api.search.builder.PageableBuilder;
import io.gravitee.repository.management.api.search.builder.SortableBuilder;
import io.gravitee.repository.management.model.Promotion;
import io.gravitee.repository.management.model.PromotionAuthor;
import io.gravitee.repository.management.model.PromotionStatus;
import io.gravitee.rest.api.model.EnvironmentEntity;
import io.gravitee.rest.api.model.UserEntity;
import io.gravitee.rest.api.model.common.Pageable;
import io.gravitee.rest.api.model.common.Sortable;
import io.gravitee.rest.api.model.promotion.*;
import io.gravitee.rest.api.service.ApiService;
import io.gravitee.rest.api.service.EnvironmentService;
import io.gravitee.rest.api.service.UserService;
import io.gravitee.rest.api.service.cockpit.command.bridge.operation.BridgeOperation;
import io.gravitee.rest.api.service.cockpit.services.CockpitReply;
import io.gravitee.rest.api.service.cockpit.services.CockpitReplyStatus;
import io.gravitee.rest.api.service.cockpit.services.CockpitService;
import io.gravitee.rest.api.service.common.GraviteeContext;
import io.gravitee.rest.api.service.common.RandomString;
import io.gravitee.rest.api.service.exceptions.BridgeOperationException;
import io.gravitee.rest.api.service.exceptions.TechnicalManagementException;
import io.gravitee.rest.api.service.impl.AbstractService;
import io.gravitee.rest.api.service.jackson.ser.api.ApiSerializer;
import io.gravitee.rest.api.service.promotion.PromotionService;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

/**
 * @author Florent CHAMFROY (florent.chamfroy at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class PromotionServiceImpl extends AbstractService implements PromotionService {

    private final Logger LOGGER = LoggerFactory.getLogger(PromotionServiceImpl.class);

    private final ApiService apiService;
    private final CockpitService cockpitService;
    private final PromotionRepository promotionRepository;
    private final EnvironmentService environmentService;
    private final UserService userService;

    public PromotionServiceImpl(
        ApiService apiService,
        CockpitService cockpitService,
        PromotionRepository promotionRepository,
        EnvironmentService environmentService,
        UserService userService
    ) {
        this.apiService = apiService;
        this.cockpitService = cockpitService;
        this.promotionRepository = promotionRepository;
        this.environmentService = environmentService;
        this.userService = userService;
    }

    @Override
    public List<PromotionTargetEntity> listPromotionTargets(String organizationId, String environmentId) {
        EnvironmentEntity environmentEntity = environmentService.findById(environmentId);

        final CockpitReply<List<PromotionTargetEntity>> listCockpitReply = this.cockpitService.listPromotionTargets(organizationId);
        if (listCockpitReply.getStatus() == CockpitReplyStatus.SUCCEEDED) {
            return listCockpitReply
                .getReply()
                .stream()
                .filter(target -> !target.getId().equals(environmentEntity.getCockpitId()))
                .collect(Collectors.toList());
        }
        throw new BridgeOperationException(BridgeOperation.LIST_ENVIRONMENT);
    }

    @Override
    public PromotionEntity promote(String apiId, PromotionRequestEntity promotionRequest, String userId) {
        // TODO: do we have to use filteredFields like for duplicate (i think no need members and groups)
        // FIXME: can we get the version from target environment
        String apiDefinition = apiService.exportAsJson(apiId, ApiSerializer.Version.DEFAULT.getVersion());

        EnvironmentEntity currentEnvironmentEntity = environmentService.findById(GraviteeContext.getCurrentEnvironment());
        UserEntity author = userService.findById(userId);

        // FIXME, does promotion already exist
        Promotion promotionToSave = convert(apiId, apiDefinition, currentEnvironmentEntity, promotionRequest, author);
        promotionToSave.setId(RandomString.generate());
        Promotion createdPromotion = null;
        try {
            createdPromotion = promotionRepository.create(promotionToSave);
        } catch (TechnicalException exception) {
            throw new TechnicalManagementException(
                String.format("An error occurs while trying to create a promotion request for API %s", apiId),
                exception
            );
        }

        PromotionEntity promotionEntity = convert(createdPromotion);
        CockpitReply<PromotionEntity> cockpitReply = cockpitService.requestPromotion(promotionEntity);
        if (cockpitReply.getStatus() != CockpitReplyStatus.SUCCEEDED) {
            throw new BridgeOperationException(BridgeOperation.PROMOTE_API);
        }

        promotionEntity.setStatus(PromotionEntityStatus.TO_BE_VALIDATED);
        try {
            promotionRepository.update(convert(promotionEntity));
        } catch (TechnicalException exception) {
            throw new TechnicalManagementException(
                String.format("An error occurs while trying to update promotion %s", promotionEntity.getId()),
                exception
            );
        }

        return promotionEntity;
    }

    @Override
    public PromotionEntity createOrUpdate(PromotionEntity promotionEntity) {
        try {
            final Optional<Promotion> existingPromotion = promotionRepository.findById(promotionEntity.getId());

            final Promotion promotion = convert(promotionEntity);

            Promotion createdOrUpdatedPromotion;

            if (existingPromotion.isPresent()) {
                LOGGER.debug("Updating existing promotion: {}", promotion.getId());
                createdOrUpdatedPromotion = promotionRepository.update(promotion);
            } else {
                LOGGER.debug("Creating promotion: {}", promotion.getId());
                createdOrUpdatedPromotion = promotionRepository.create(promotion);
            }

            return convert(createdOrUpdatedPromotion);
        } catch (TechnicalException e) {
            LOGGER.error("An error occurs while trying to create or update a promotion using its id {}", promotionEntity.getId(), e);
            throw new TechnicalManagementException(
                "An error occurs while trying to create or update a promotion using its id {}" + promotionEntity.getId(),
                e
            );
        }
    }

    @Override
    public Page<PromotionEntity> search(PromotionQuery query, Sortable sortable, Pageable pageable) {
        try {
            LOGGER.debug("Searching promotions");

            PromotionCriteria criteria = queryToCriteriaBuilder(query).build();

            Page<Promotion> promotions = promotionRepository.search(criteria, buildSortable(sortable), buildPageable(pageable));

            List<PromotionEntity> entities = promotions.getContent().stream().map(this::convert).collect(Collectors.toList());

            LOGGER.debug("Searching promotions - Done with {} elements", entities.size());

            return new Page<>(entities, promotions.getPageNumber() + 1, (int) promotions.getPageElements(), promotions.getTotalElements());
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to search tickets", ex);
            throw new TechnicalManagementException("An error occurs while trying to search tickets", ex);
        }
    }

    private Promotion convert(
        String apiId,
        String apiDefinition,
        EnvironmentEntity source,
        PromotionRequestEntity target,
        UserEntity author
    ) {
        PromotionAuthor promotionAuthor = new PromotionAuthor();
        promotionAuthor.setUserId(author.getId());
        promotionAuthor.setDisplayName(author.getDisplayName());
        promotionAuthor.setEmail(author.getEmail());
        promotionAuthor.setPicture(author.getPicture());
        promotionAuthor.setSource(author.getSource());
        promotionAuthor.setSourceId(author.getSourceId());

        Promotion promotion = new Promotion();
        promotion.setCreatedAt(new Date());
        promotion.setStatus(PromotionStatus.CREATED);
        promotion.setApiDefinition(apiDefinition);
        promotion.setApiId(apiId);

        promotion.setSourceEnvCockpitId(source.getCockpitId());
        promotion.setSourceEnvName(source.getName());

        promotion.setTargetEnvCockpitId(target.getTargetEnvCockpitId());
        promotion.setTargetEnvName(target.getTargetEnvName());

        promotion.setAuthor(promotionAuthor);

        return promotion;
    }

    private PromotionEntity convert(Promotion promotion) {
        PromotionEntityAuthor promotionEntityAuthor = new PromotionEntityAuthor();
        promotionEntityAuthor.setUserId(promotion.getAuthor().getUserId());
        promotionEntityAuthor.setDisplayName(promotion.getAuthor().getDisplayName());
        promotionEntityAuthor.setEmail(promotion.getAuthor().getEmail());
        promotionEntityAuthor.setPicture(promotion.getAuthor().getPicture());
        promotionEntityAuthor.setSource(promotion.getAuthor().getSource());
        promotionEntityAuthor.setSourceId(promotion.getAuthor().getSourceId());

        PromotionEntity promotionEntity = new PromotionEntity();
        promotionEntity.setId(promotion.getId());
        promotionEntity.setApiId(promotion.getApiId());
        promotionEntity.setCreatedAt(promotion.getCreatedAt());
        promotionEntity.setUpdatedAt(promotion.getUpdatedAt());
        promotionEntity.setSourceEnvCockpitId(promotion.getSourceEnvCockpitId());
        promotionEntity.setSourceEnvName(promotion.getSourceEnvName());
        promotionEntity.setTargetEnvCockpitId(promotion.getTargetEnvCockpitId());
        promotionEntity.setTargetEnvName(promotion.getTargetEnvName());
        promotionEntity.setApiDefinition(promotion.getApiDefinition());
        promotionEntity.setStatus(convert(promotion.getStatus()));
        promotionEntity.setAuthor(promotionEntityAuthor);

        return promotionEntity;
    }

    private Promotion convert(PromotionEntity promotionEntity) {
        PromotionAuthor promotionAuthor = new PromotionAuthor();
        promotionAuthor.setUserId(promotionEntity.getAuthor().getUserId());
        promotionAuthor.setDisplayName(promotionEntity.getAuthor().getDisplayName());
        promotionAuthor.setEmail(promotionEntity.getAuthor().getEmail());
        promotionAuthor.setPicture(promotionEntity.getAuthor().getPicture());
        promotionAuthor.setSource(promotionEntity.getAuthor().getSource());
        promotionAuthor.setSourceId(promotionEntity.getAuthor().getSourceId());

        Promotion promotion = new Promotion();
        promotion.setId(promotionEntity.getId());
        promotion.setApiId(promotionEntity.getApiId());
        promotion.setCreatedAt(promotionEntity.getCreatedAt());
        promotion.setUpdatedAt(promotionEntity.getUpdatedAt());
        promotion.setSourceEnvCockpitId(promotionEntity.getSourceEnvCockpitId());
        promotion.setSourceEnvName(promotionEntity.getSourceEnvName());
        promotion.setTargetEnvCockpitId(promotionEntity.getTargetEnvCockpitId());
        promotion.setTargetEnvName(promotionEntity.getTargetEnvName());
        promotion.setApiDefinition(promotionEntity.getApiDefinition());
        promotion.setStatus(convert(promotionEntity.getStatus()));
        promotion.setAuthor(promotionAuthor);

        return promotion;
    }

    private PromotionEntityStatus convert(PromotionStatus promotionStatus) {
        return PromotionEntityStatus.valueOf(promotionStatus.name());
    }

    private PromotionStatus convert(PromotionEntityStatus promotionEntityStatus) {
        return PromotionStatus.valueOf(promotionEntityStatus.name());
    }

    private PromotionCriteria.Builder queryToCriteriaBuilder(PromotionQuery query) {
        final PromotionCriteria.Builder builder = new PromotionCriteria.Builder();
        if (query == null) {
            return builder;
        }

        if (!CollectionUtils.isEmpty(query.getTargetEnvCockpitIds())) {
            builder.targetEnvCockpitIds(query.getTargetEnvCockpitIds().toArray(new String[0]));
        }
        if (query.getStatus() != null) {
            builder.status(convert(query.getStatus()));
        }

        return builder;
    }

    private io.gravitee.repository.management.api.search.Sortable buildSortable(Sortable sortable) {
        if (sortable == null) {
            return null;
        }
        return new SortableBuilder().field(sortable.getField()).order(sortable.isAscOrder() ? Order.ASC : Order.DESC).build();
    }

    private io.gravitee.repository.management.api.search.Pageable buildPageable(Pageable pageable) {
        if (pageable == null) {
            return null;
        }
        return new PageableBuilder().pageNumber(pageable.getPageNumber() - 1).pageSize(pageable.getPageSize()).build();
    }
}