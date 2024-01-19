#!/usr/bin/env python3

"""
This file demonstrates how to use sherpa-ncnn Python API to recognize
a single file.

Please refer to
https://k2-fsa.github.io/sherpa/ncnn/index.html
to install sherpa-ncnn and to download the pre-trained models
used in this file.
"""

import time
import wave

import numpy as np
import sherpa_ncnn

from pydub import AudioSegment
import re
import cn2an

def main():
    # Please refer to https://k2-fsa.github.io/sherpa/ncnn/index.html
    # to download the model files
    recognizer = sherpa_ncnn.Recognizer(
        tokens="../sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13/tokens.txt",
        encoder_param="../sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13/encoder_jit_trace-pnnx.ncnn.param",
        encoder_bin="../sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13/encoder_jit_trace-pnnx.ncnn.bin",
        decoder_param="../sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13/decoder_jit_trace-pnnx.ncnn.param",
        decoder_bin="../sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13/decoder_jit_trace-pnnx.ncnn.bin",
        joiner_param="../sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13/joiner_jit_trace-pnnx.ncnn.param",
        joiner_bin="../sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13/joiner_jit_trace-pnnx.ncnn.bin",
        num_threads=4,
    )
    filename = 'test_wavs/ElevatorTest.mp3'
    audio = AudioSegment.from_mp3(filename)
    # 将音频导出为wav文件
    wav_file_path = 'test_wavs/output.wav'
    audio.export(wav_file_path, format='wav')

    with wave.open(wav_file_path) as f:
        # Note: If wave_file_sample_rate is different from
        # recognizer.sample_rate, we will do resampling inside sherpa-ncnn
        wave_file_sample_rate = f.getframerate()
        num_channels = f.getnchannels()
        assert f.getsampwidth() == 2, f.getsampwidth()  # it is in bytes
        num_samples = f.getnframes()
        samples = f.readframes(num_samples)
        samples_int16 = np.frombuffer(samples, dtype=np.int16)
        samples_int16 = samples_int16.reshape(-1, num_channels)[:, 0]
        samples_float32 = samples_int16.astype(np.float32)

        samples_float32 = samples_float32 / 32768

    # simulate streaming
    chunk_size = int(0.1 * wave_file_sample_rate)  # 0.1 seconds
    start = 0
    while start < samples_float32.shape[0]:
        end = start + chunk_size
        end = min(end, samples_float32.shape[0])
        recognizer.accept_waveform(wave_file_sample_rate, samples_float32[start:end])
        start = end
        time.sleep(0.1)

    tail_paddings = np.zeros(int(wave_file_sample_rate * 0.5), dtype=np.float32)
    recognizer.accept_waveform(wave_file_sample_rate, tail_paddings)
    recognizer.input_finished()
    text = recognizer.text
    output_file_path = 'output/output.txt'  # 设置输出文件的路径
    if text:
        with open(output_file_path, 'w') as file:
            file.write(text)
        print(text)
    with open(output_file_path, 'r') as file:
        text = file.read()


if __name__ == "__main__":
    main()
