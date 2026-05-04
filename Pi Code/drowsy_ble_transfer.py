#!/usr/bin/env python3
from picamera2 import Picamera2
from picamera2.devices import IMX500
from picamera2.devices.imx500 import NetworkIntrinsics
import cv2
import time
import os
import socket
import subprocess
from datetime import datetime

class BLEMessageSender:
    def __init__(self):
        self.connected = False
        self.client_socket = None

    def setup_bluetooth(self):
        print("Configuring Bluetooth...")
        subprocess.run(['/usr/local/bin/bt-configure-ble.sh'],
                      stderr=subprocess.DEVNULL)
        time.sleep(2)
        print("Bluetooth configured\n")

    def get_local_address(self):
        try:
            result = subprocess.run(['hciconfig', 'hci0'],
                                  capture_output=True, text=True)
            for line in result.stdout.split('\n'):
                if 'BD Address' in line:
                    return line.split('BD Address:')[1].strip().split()[0]
        except:
            pass
        return None

    def start_advertising(self):
        print("Starting BLE advertising...")

        subprocess.run(['sudo', 'hciconfig', 'hci0', 'noleadv'],
                      stderr=subprocess.DEVNULL)

        subprocess.run([
            'sudo', 'hcitool', '-i', 'hci0', 'cmd',
            '0x08', '0x0008',
            '0x0B',
            '0x09',
            '0x52', '0x50', '0x69', '0x2D', '0x42', '0x4C', '0x45'  # "RPi-BLE"
        ], stderr=subprocess.DEVNULL)

        subprocess.run(['sudo', 'hciconfig', 'hci0', 'leadv', '3'],
                      stderr=subprocess.DEVNULL)

        print("BLE advertising active\n")

    def wait_for_connection(self):
        local_addr = self.get_local_address()
        if not local_addr:
            print("Could not get Bluetooth address")
            return False

        print(f"Local Address: {local_addr}")
        print("\nWaiting for Android app to connect...")
        print("   Open app and tap 'Connect to Pi'\n")

        sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM,
                            socket.BTPROTO_RFCOMM)

        try:
            sock.bind((local_addr, 1))
            sock.listen(1)

            self.client_socket, address = sock.accept()
            self.connected = True

            print(f"Connected to: {address}\n")
            print("=" * 70 + "\n")
            return True

        except Exception as e:
            print(f"Connection error: {e}")
            return False

    def send_message(self, message):
        if not self.connected or not self.client_socket:
            return False

        try:
            full_msg = f"{message}\n"
            self.client_socket.send(full_msg.encode('utf-8'))
            return True
        except:
            self.connected = False
            return False

    def send_detection(self, label, confidence, count):
        timestamp = datetime.now().strftime("%H:%M:%S")
        message = f"DETECT|{label}|{confidence:.2f}|{count}|{timestamp}"
        return self.send_message(message)

    def close(self):
        if self.client_socket:
            try:
                self.client_socket.close()
            except:
                pass
        self.connected = False

def run_yolo_with_ble():
    print("=" * 70)
    print("DROWSINESS DETECTION + BLE ALERTS")
    print("=" * 70)
    print()

    # Initialize BLE
    ble = BLEMessageSender()
    ble.setup_bluetooth()
    ble.start_advertising()

    if not ble.wait_for_connection():
        print("Failed to connect. Exiting.")
        return

    # Load IMX500 model
    model_path = "/home/user/Desktop/drowsiness_model/network.rpk"
    imx500 = IMX500(model_path)
    intrinsics = imx500.network_intrinsics

    if not intrinsics:
        intrinsics = NetworkIntrinsics()
        intrinsics.task = "object detection"
        intrinsics.labels = ['Awake', 'Drowsy', 'Yawning']

    print(f"Model loaded: {model_path}")
    print(f"Labels: {intrinsics.labels if intrinsics.labels else ['Awake', 'Drowsy', 'Yawning']}\n")

    ble.send_message("READY|Detection started")

    print("=" * 70 + "\n")

    # Initialize camera
    picam2 = Picamera2(imx500.camera_num)
    config = picam2.create_preview_configuration(
        controls={"FrameRate": 30},
        buffer_count=12
    )
    picam2.configure(config)

    imx500.show_network_fw_progress_bar()
    picam2.start()

    frame_count = 0
    drowsy_count = 0
    awake_count = 0
    yawning_count = 0
    sent_count = 0

    labels = ['Awake', 'Drowsy', 'Yawning']
    last_results = []

    def parse_detections(metadata):
        nonlocal last_results

        np_outputs = imx500.get_outputs(metadata, add_batch=True)
        if np_outputs is None:
            return last_results

        boxes, scores, classes = np_outputs[0][0], np_outputs[1][0], np_outputs[2][0]

        detections = []
        for box, score, cls in zip(boxes, scores, classes):
            if score >= 0.5:
                detections.append({
                    'class': int(cls),
                    'score': float(score),
                    'box': box
                })

        last_results = detections
        return detections

    print("Starting detection and messaging...")
    print("Press CTRL+C to stop\n")

    try:
        while True:
            if not ble.connected:
                print("\nBLE disconnected. Stopping...")
                break

            metadata = picam2.capture_metadata()
            detections = parse_detections(metadata)

            for detection in detections:
                cls = detection['class']
                score = detection['score']
                label = labels[cls] if cls < 3 else 'Unknown'

                if label == 'Drowsy':
                    drowsy_count += 1

                    print(f"\n[{datetime.now().strftime('%H:%M:%S')}] Drowsy! Score: {score:.2f}")

                    if ble.send_detection("Drowsy", score, drowsy_count):
                        sent_count += 1
                        print(f"           Alert sent to phone")
                        print(f"           Alerts sent: {sent_count}\n")
                    else:
                        print(f"           Failed to send alert\n")

                elif label == 'Awake':
                    awake_count += 1
                    if awake_count % 10 == 0:
                        print(f"[{datetime.now().strftime('%H:%M:%S')}] Awake ({awake_count})")
                        ble.send_detection("Awake", score, awake_count)

                elif label == 'Yawning':
                    yawning_count += 1
                    print(f"[{datetime.now().strftime('%H:%M:%S')}] Yawning ({yawning_count})")

                    if ble.send_detection("Yawning", score, yawning_count):
                        sent_count += 1
                        print(f"           Alert sent to phone\n")

            frame_count += 1
            if frame_count % 100 == 0:
                print(f"\nFrames: {frame_count} | Awake: {awake_count} | Drowsy: {drowsy_count} | Yawning: {yawning_count}\n")

            time.sleep(0.05)

    except KeyboardInterrupt:
        print("\n\nStopped by user")
    finally:
        picam2.stop()

        ble.send_message(f"COMPLETE|Detection ended|Drowsy: {drowsy_count}, Yawning: {yawning_count}")
        time.sleep(1)
        ble.close()

    print("\n" + "=" * 70)
    print("DETECTION SUMMARY")
    print("=" * 70)
    print(f"Frames processed:        {frame_count}")
    print(f"Awake detections:        {awake_count}")
    print(f"Drowsy detections:       {drowsy_count}")
    print(f"Yawning detections:      {yawning_count}")
    print(f"Alerts sent to phone:    {sent_count}")
    print("=" * 70 + "\n")

if __name__ == "__main__":
    print("YOLO DROWSINESS DETECTION - BLE ALERTS")
    print("=" * 70)
    print("Message format: DETECT|Label|Confidence|Count|Time")
    print("=" * 70 + "\n")

    try:
        run_yolo_with_ble()
    except Exception as e:
        print(f"\nError: {e}")
        import traceback
        traceback.print_exc()

    print("\nComplete!\n")