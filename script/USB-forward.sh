#!/bin/bash

PORT=5740

# 获取设备列表（跳过标题行）
devices=($(adb devices | awk 'NR>1 {print $1}' | grep -v "^$"))
device_count=${#devices[@]}

# 检查是否连接了两个设备
if [ $device_count -ne 2 ]; then
    echo "Detected devices: $device_count. Please connect exactly two phones!"
    echo "Connected devices:"
    for dev_id in "${devices[@]}"; do
        dev_name=$(adb -s "$dev_id" shell 'settings get global device_name 2>/dev/null' | tr -d '\r')
        echo "${dev_name:-Unknown}:$dev_id"
    done
    read -n1 -p "Press any key to exit..."
    exit 1
fi

# 获取设备显示名称
get_device_display() {
    local dev_id=$1
    local dev_name=$(adb -s "$dev_id" shell 'settings get global device_name 2>/dev/null' | tr -d '\r')
    echo "${dev_name:-Unknown}:$dev_id"
}

# 显示设备选择菜单
echo
echo "Please select the device to be Phone A (server):"
echo "1. $(get_device_display "${devices[0]}")"
echo "2. $(get_device_display "${devices[1]}")"
read -n1 -p "Enter a number (1 or 2): " choice
echo

# 处理用户选择
case $choice in
    1)
        deviceA=${devices[0]}
        deviceB=${devices[1]}
        ;;
    2)
        deviceA=${devices[1]}
        deviceB=${devices[0]}
        ;;
    *)
        echo "Invalid selection, exiting."
        read -n1 -p "Press any key to exit..."
        exit 1
        ;;
esac

echo
echo "Selected Phone A: $deviceA"
echo "Selected Phone B: $deviceB"

# 设置端口转发
echo
echo "Setting up port forwarding..."
adb -s "$deviceA" forward tcp:$PORT tcp:$PORT
adb -s "$deviceB" reverse tcp:$PORT tcp:$PORT

echo
echo "Port forwarding setup complete!"
read -n1 -p "Press any key to exit..."
exit 0