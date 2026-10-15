#include "../hy_mt_prompt.h"
#include <cassert>
#include <iostream>
#include <iterator>

int main() {
    const std::string input(std::istreambuf_iterator<char>(std::cin), {});
    const auto rendered = hy_mt::render_single_user(hy_mt::expected_template, input);
    assert(rendered == "<｜hy_begin▁of▁sentence｜><｜hy_User｜>" + input + "<｜hy_Assistant｜>");
    bool rejected = false;
    try { hy_mt::render_single_user("changed template", input); }
    catch (const std::runtime_error &) { rejected = true; }
    assert(rejected);
    rejected = false;
    try { hy_mt::render_single_user(nullptr, input); }
    catch (const std::runtime_error &) { rejected = true; }
    assert(rejected);
    std::cout << rendered;
}
