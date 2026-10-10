#pragma once
#include <stddef.h>
#include <string.h>

namespace fof {
// 줄이 완성되기 전에는 아무 명령도 실행하지 않습니다. 초과/NUL 입력은
// 다음 LF까지 버린 뒤 오류 한 번을 보고합니다. KEY를 포함한 입력은 지웁니다.
class SerialLine {
 public:
  template<class Port, class Handler, class Capacity>
  void poll(Port& port, Handler handler, Capacity capacity) {
    for (unsigned budget = 0; budget < 128 && port.available() > 0 && capacity(); ++budget) {
      const char c = static_cast<char>(port.read());
      if (c == '\r') continue;
      if (c == '\n') {
        line_[length_] = 0;
        if (overflow_) handler(nullptr);
        else if (length_) handler(line_);
        memset(line_, 0, sizeof(line_));
        length_ = 0;
        overflow_ = false;
      } else if (!c || length_ == sizeof(line_) - 1) overflow_ = true;
      else if (!overflow_) line_[length_++] = c;
    }
  }
 private:
  char line_[80] = {};
  size_t length_ = 0;
  bool overflow_ = false;
};
} // namespace fof
