package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.valobj.intent.IntentResultVO;

public interface IIntentService {
    IntentResultVO classify(String sessionId, String userId, String message);
}
