# 编译器设置
CXX = g++
CXXFLAGS = -std=c++17 -Wall -Wextra -I/usr/local/include -I/usr/local/include/httplib -I/usr/local/include/nlohmann

# 链接器设置
LDFLAGS = -L/usr/local/lib -lcurl -lsqlite3

SRCDIR = src/main/cpp
OBJDIR = build/obj
BINDIR = build/bin

SOURCES = $(wildcard $(SRCDIR)/*.cpp)
OBJECTS = $(SOURCES:$(SRCDIR)/%.cpp=$(OBJDIR)/%.o)
TARGET = $(BINDIR)/copilot_api_server

$(shell mkdir -p $(OBJDIR) $(BINDIR))

# 默认目标
all: $(TARGET)

# 链接目标
$(TARGET): $(OBJECTS)
	$(CXX) $(OBJECTS) -o $(TARGET) $(LDFLAGS)

# 编译源文件
$(OBJDIR)/%.o: $(SRCDIR)/%.cpp
	$(CXX) $(CXXFLAGS) -c $< -o $@

# 清理构建文件
clean:
	rm -rf $(OBJDIR)/* $(BINDIR)/*

# 安装依赖
deps:
	sudo apt-get update
	sudo apt-get install -y libcurl4-openssl-dev libsqlite3-dev

# 运行目标
run: $(TARGET)
	./$(TARGET)

.PHONY: all clean deps run
