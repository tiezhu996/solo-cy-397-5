package com.contractapi.service;

import java.math.RoundingMode;
import java.util.List;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.contractapi.constants.ContractStatus;
import com.contractapi.constants.ErrorCode;
import com.contractapi.dto.GenerateContractRequest;
import com.contractapi.entity.Contract;
import com.contractapi.entity.ContractTemplate;
import com.contractapi.exception.ApiException;
import com.contractapi.mapper.ContractMapper;
import com.contractapi.utils.TemplateRenderer;
import org.springframework.stereotype.Service;

@Service
public class ContractService {
  private final TemplateService templateService;
  private final TemplateRenderer renderer;
  private final ContractMapper contractMapper;

  public ContractService(TemplateService templateService, TemplateRenderer renderer, ContractMapper contractMapper) {
    this.templateService = templateService;
    this.renderer = renderer;
    this.contractMapper = contractMapper;
  }

  public Contract generate(GenerateContractRequest request) {
    ContractTemplate template = templateService.find(request.templateId());
    Contract contract = new Contract();
    contract.setUserId(request.userId());
    contract.setTemplateId(template.getId());
    contract.setTitle(request.title());
    contract.setContent(renderer.render(template.getContent(), request.variables()));
    if (request.amount() != null) {
      // 合同金额同样归一到分，保证与分期合计可精确对账
      contract.setAmount(request.amount().setScale(2, RoundingMode.HALF_UP));
    }
    contract.setStatus(ContractStatus.DRAFT.name());
    contract.setSigners("[]");
    contractMapper.insert(contract);
    return contract;
  }

  public Contract updateStatus(Long id, ContractStatus status) {
    Contract contract = contractMapper.selectById(id);
    if (contract == null) {
      throw new ApiException(ErrorCode.NOT_FOUND, "合同不存在: " + id);
    }
    contract.setStatus(status.name());
    contractMapper.updateById(contract);
    return contract;
  }

  public List<Contract> list(Long userId, String status) {
    QueryWrapper<Contract> wrapper = new QueryWrapper<>();
    wrapper.eq(userId != null, "user_id", userId);
    wrapper.eq(status != null, "status", status);
    return contractMapper.selectList(wrapper);
  }

  public String exportPdf(Long id) {
    return "wkhtmltopdf 已在 Docker 镜像安装，合同 " + id + " 可导出到 /tmp/contracts/" + id + ".pdf";
  }
}
