// WebDemo の MeshStandardMaterial(side: DoubleSide) 相当。
// 裏面は法線を反転してライティングするため、OBJ の面向きが揃っていなくても透明化しない。
Shader "AIDY/SatinDoubleSided"
{
    Properties
    {
        _Color ("Color", Color) = (0.627451, 0.627451, 0.627451, 1)
        _Metallic ("Metallic", Range(0, 1)) = 0.35
        _Glossiness ("Smoothness", Range(0, 1)) = 0.6
    }
    SubShader
    {
        Tags { "RenderType" = "Opaque" "Queue" = "Geometry" }
        Cull Off

        CGPROGRAM
        #pragma surface surf Standard fullforwardshadows addshadow
        #pragma target 3.0

        struct Input
        {
            float facing : VFACE;
        };

        fixed4 _Color;
        half _Metallic;
        half _Glossiness;

        void surf (Input IN, inout SurfaceOutputStandard o)
        {
            o.Albedo = _Color.rgb;
            o.Metallic = _Metallic;
            o.Smoothness = _Glossiness;
            o.Normal = float3(0, 0, IN.facing > 0 ? 1 : -1);
            o.Alpha = 1;
        }
        ENDCG
    }
    FallBack "Diffuse"
}
