# ====== 路径与通用变量 ======
SRC_DIR := src/main/cpp
OBJ_DIR := build/obj
BIN_DIR := build/bin

SOURCES := $(wildcard $(SRC_DIR)/*.cpp)
OBJECTS := $(patsubst $(SRC_DIR)/%.cpp,$(OBJ_DIR)/%.o,$(SOURCES))

.PHONY: all clean all_static all_static_win check_dirs

# ====== 默认动态构建（Linux）======
CXX      := g++
CXXFLAGS := -std=c++20 -O3 -Wall -Wextra -I/usr/local/include
LDFLAGS  := -L/usr/local/lib
LIBS := -static -Wl,--start-group \
        -lcurl -lssl -lcrypto -lnghttp2 -lsqlite3 -lzstd -lz -lresolv \
        -Wl,--end-group -ldl -lpthread


TARGET   := $(BIN_DIR)/copilot-api

all: $(TARGET)

$(TARGET): $(OBJECTS) | check_dirs
	$(CXX) $(OBJECTS) -o $@ $(LDFLAGS) $(LIBS)

$(OBJ_DIR)/%.o: $(SRC_DIR)/%.cpp | check_dirs
	$(CXX) $(CXXFLAGS) -c $< -o $@

check_dirs:
	@mkdir -p $(OBJ_DIR) $(BIN_DIR)

clean:
	rm -rf $(OBJ_DIR) $(BIN_DIR)

# ====== Linux 全静态构建 ======
LINUX_STATIC := $(BIN_DIR)/copilot-linux-static
all_static: CXXFLAGS += -static -fno-gnu-unique
all_static: LDFLAGS  += -static
all_static: $(OBJECTS) | check_dirs
	$(CXX) $(OBJECTS) -o $(LINUX_STATIC) $(LDFLAGS) $(LIBS)

# ====== Windows x86_64 交叉静态 ======
CROSS        := x86_64-w64-mingw32
PREFIX       := /usr/$(CROSS)/static
WIN_CXX      := $(CROSS)-g++

WIN_CXXFLAGS := -std=c++20 -O3 -Wall -Wextra \
                -DNGHTTP2_STATICLIB -DCURL_STATICLIB -DOPENSSL_STATIC \
                -I$(PREFIX)/include

WIN_LDFLAGS  := -static -static-libgcc -static-libstdc++ -L$(PREFIX)/lib
WIN_LIBS     := \
  -lcurl -lssl -lcrypto -lsqlite3 -lnghttp2 -lz -lzstd \
  -lwinpthread -lws2_32 -lcrypt32 -lbcrypt -lwinmm -lgdi32

WIN_TARGET   := $(BIN_DIR)/copilot-windows-static.exe

all_static_win: CXX      := $(WIN_CXX)
all_static_win: CXXFLAGS := $(WIN_CXXFLAGS)
all_static_win: LDFLAGS  := $(WIN_LDFLAGS)
all_static_win: LIBS     := $(WIN_LIBS)
all_static_win: $(OBJECTS) | check_dirs
	$(CXX) $(OBJECTS) -o $(WIN_TARGET) $(LDFLAGS) $(LIBS)

# ====== 可选：使用 pkg-config 生成依赖（示例）======
# 如果依赖装得很标准，可改为：
# PKG_LIBS   := $(shell pkg-config --static --libs libcurl openssl nghttp2 zlib zstd sqlite3)
# PKG_CFLAGS := $(shell pkg-config --cflags libcurl openssl nghttp2 zlib zstd sqlite3)
# 然后把 CXXFLAGS += $(PKG_CFLAGS)，LIBS := -Wl,--start-group $(PKG_LIBS) -Wl,--end-group -ldl -lpthread
