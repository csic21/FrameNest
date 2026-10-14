#pragma once
#include <stdexcept>
#include <string>

namespace hy_mt {
// Exact tokenizer.chat_template from the pinned, SHA-256-verified GGUF.
inline constexpr const char * expected_template = R"HY({% if messages[0]['role'] == 'system' %}{% set loop_messages = messages[1:] %}{% set system_message = messages[0]['content'] %}<｜hy_begin▁of▁sentence｜>{{ system_message }}<｜hy_place▁holder▁no▁3｜>{% else %}{% set loop_messages = messages %}<｜hy_begin▁of▁sentence｜>{% endif %}{% for message in loop_messages %}{% if message['role'] == 'user' %}<｜hy_User｜>{{ message['content'] }}{% elif message['role'] == 'assistant' %}<｜hy_Assistant｜>{{ message['content'] }}<｜hy_place▁holder▁no▁2｜>{% endif %}{% endfor %}{% if add_generation_prompt %}<｜hy_Assistant｜>{% else %}<｜hy_place▁holder▁no▁8｜>{% endif %})HY";

inline std::string render_single_user(const char * model_template, const std::string & content) {
    if (!model_template || std::string(model_template) != expected_template) {
        throw std::runtime_error("Unsupported local model chat template");
    }
    // This is exactly the template's one-user/no-system/add_generation_prompt branch.
    // Do not use llama_chat_apply_template's heuristic legacy template detector.
    return "<｜hy_begin▁of▁sentence｜><｜hy_User｜>" + content + "<｜hy_Assistant｜>";
}
} // namespace hy_mt
