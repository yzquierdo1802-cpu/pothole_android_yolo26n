# Baches Perú — Android (YOLO26n-seg)

On-device instance segmentation of potholes from a windshield-mounted phone.

Paper: *Detección y segmentación de baches en vista de parabrisas: hold-out agrupado y despliegue en smartphone* (Yzquierdo Sanchez & Chambi Aguilar, 2026).

## Repository

https://github.com/yzquierdo1802-cpu/pothole_android_yolo26n

Project folder: `POTHOLE_ANDROID_YOLO26N_GPS_FASTFIX/`

Dataset / test split: https://github.com/BenavidezYzquierdoSanchez/data_pothole  
DOI: https://doi.org/10.5281/zenodo.23028710

## What the app does

- YOLO26n-seg INT8 TensorFlow Lite (`modelomovil-v512.tflite`, 3.24 MB)
- Input 416 px (detector) / ROI 512 px
- Instance mask overlay, GPS stamp, local reports (Room)
- Visual severity from mask area (not field-measured m²)
- Measured latency on the development phone: **166–170 ms** (~5 FPS)

Metres shown on screen are a **monocular ground-plane estimate**. They are not a validated length.

## Build

Android Studio (Giraffe or newer), JDK 17.

Open `POTHOLE_ANDROID_YOLO26N_GPS_FASTFIX/` as the project root. The `.tflite` must sit on the app assets path used by the inference class.

## What this is not

- Not the training code for YOLO26s-seg
- Not a night-robust detector (night extra mAP@0.5 ≈ 0.18)
- Not a surveying tool

## Licence

MIT for source. The TFLite weight is research-only unless a separate licence is attached.

## Contact

benavidezy@upeu.edu.pe  
jeson.chabi@upeu.edu.pe
