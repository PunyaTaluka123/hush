# Host tests for the firmware core, and the shared library the simulator loads.
#   make -C firmware/tests/host          run all host tests
#   make -C firmware/tests/host lib      build sim/libhushcore.so for the simulator
CXX ?= c++
FLAGS = -std=c++17 -O2 -Wall -Wextra -Werror -I../../lib
OUT = build
LIB = ../../../sim/libhushcore.so
DEPS = ../../lib/hush.h ../../lib/hush_core.h ../../lib/pattern_detector.h traces.h

test: $(OUT)/test_pattern_detector $(OUT)/test_core
	$(OUT)/test_pattern_detector
	$(OUT)/test_core

$(OUT)/%: %.cpp $(DEPS)
	@mkdir -p $(OUT)
	$(CXX) $(FLAGS) $< -o $@

lib: $(LIB)
$(LIB): ../../lib/hush_capi.cpp $(DEPS)
	$(CXX) $(FLAGS) -shared -fPIC $< -o $@

.PHONY: test lib
